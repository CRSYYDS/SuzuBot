// Read-only Android arm64-v8a probe against pinned liba32android
// 57adc7252715a71a8e8492520aa8cc2629b74879: runs generated ARM32 code and
// checks the returned register / SVC trap (per
// tests/runtime/public_embedding_api.c); given an APK, lists its
// lib/armeabi-v7a libraries via upstream A32ApkLibrarySource::catalog (per
// tests/compat/a32_android_library_search_integration.cpp). It never executes a
// vendor library, never modifies the APK, never writes a file and claims no
// compatibility. Real QQ launch is NOT tested here.
//
// Usage: pico_arm64_probe [PicoOnebot.apk]
// Exit:  0 passed, 1 CPU self-test failed, 2 usage, 3 APK scan failed.

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <string_view>

#include <sys/stat.h>

#include "liba32android/liba32android.h"
#include "compat/a32_android_library_search.h"

namespace {

using namespace liba32android::compat;

constexpr std::uint32_t kCodeBase = 0x00010000U;
constexpr std::uint32_t kStopBase = 0x00030000U;

// mov r0,#42 ; bx lr ; (pad) ; svc #0x12
constexpr std::array<std::uint8_t, 20> kCode{
	0x2a, 0x00, 0xa0, 0xe3, 0x1e, 0xff, 0x2f, 0xe1, 0x00, 0x00,
	0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x12, 0x00, 0x00, 0xef,
};

struct RuntimeDeleter {
	void operator()(liba32android_runtime* r) const noexcept {
		liba32android_runtime_destroy(r);
	}
};
using Runtime = std::unique_ptr<liba32android_runtime, RuntimeDeleter>;

struct ErrorText {
	std::array<char, 256> buf{};
	liba32android_error_buffer err{buf.data(), buf.size(), 0U};
	const char* text() const { return buf[0] ? buf.data() : "(no detail)"; }
};

int fail(int code, std::string_view why, const ErrorText& e) {
	std::printf("probe.result=FAIL\nprobe.failure=%.*s (%s)\nprobe.exit_code=%d\n",
				int(why.size()), why.data(), e.text(), code);
	return code;
}

// Runs one bounded ARM32 slice; stop==0 means "no stop PC".
bool slice(liba32android_runtime* h, std::uint32_t entry, std::uint32_t count,
		   std::uint32_t stop, liba32android_execution_result& out, ErrorText& e) {
	liba32android_execution_request req{};
	req.struct_size = sizeof(req);
	req.flags = stop ? LIBA32ANDROID_EXEC_REQUEST_HAS_STOP_PC : 0U;
	req.instruction_set = LIBA32ANDROID_INSTRUCTION_SET_ARM;
	req.entry_pc = entry;
	req.regs[14] = stop;
	req.instruction_count = count;
	req.stop_pc = stop;
	out = liba32android_execution_result{};
	out.struct_size = sizeof(out);
	return liba32android_runtime_execute(h, &req, &out, &e.err) ==
		   LIBA32ANDROID_STATUS_OK;
}

int run_cpu() {
	ErrorText e;
	liba32android_runtime* raw = nullptr;
	if (liba32android_runtime_create(&raw, &e.err) != LIBA32ANDROID_STATUS_OK || !raw) {
		return fail(1, "runtime creation failed", e);
	}
	const Runtime rt{raw};
	liba32android_runtime* const h = rt.get();

	std::size_t page = 0U;
	const std::uint32_t rw = LIBA32ANDROID_MEMORY_READ | LIBA32ANDROID_MEMORY_WRITE;
	const std::uint32_t rx = LIBA32ANDROID_MEMORY_READ | LIBA32ANDROID_MEMORY_EXECUTE;
	const bool staged =
		liba32android_runtime_page_size(h, &page, &e.err) == LIBA32ANDROID_STATUS_OK &&
		page != 0U &&
		liba32android_runtime_map(h, kCodeBase, page, rw, &e.err) ==
			LIBA32ANDROID_STATUS_OK &&
		liba32android_runtime_write(h, kCodeBase, kCode.data(), kCode.size(),
									&e.err) == LIBA32ANDROID_STATUS_OK &&
		liba32android_runtime_protect(h, kCodeBase, page, rx, &e.err) ==
			LIBA32ANDROID_STATUS_OK;
	if (!staged) {
		return fail(1, "staging guest code failed", e);
	}

	liba32android_execution_result res{};
	if (!slice(h, kCodeBase, 16U, kStopBase, res, e)) {
		return fail(1, "ARM32 execution failed", e);
	}
	std::printf("cpu.arm.r0=%u\n", unsigned(res.regs[0]));
	if ((res.flags & LIBA32ANDROID_EXEC_RESULT_STOP_PC_REACHED) == 0U ||
		res.regs[0] != 42U) {
		return fail(1, "ARM32 mov r0,#42 did not return 42", e);
	}

	// svc #0x12 must surface as a trap, not an execution error.
	if (!slice(h, kCodeBase + 16U, 1U, 0U, res, e)) {
		return fail(1, "SVC execution failed", e);
	}
	std::printf("cpu.svc.immediate=0x%x\n", unsigned(res.svc_immediate));
	if ((res.flags & LIBA32ANDROID_EXEC_RESULT_SVC_TRAP) == 0U ||
		res.svc_immediate != 0x12U) {
		return fail(1, "svc #0x12 was not reported as an SVC trap", e);
	}

	if (liba32android_runtime_unmap(h, kCodeBase, page, &e.err) !=
		LIBA32ANDROID_STATUS_OK) {
		return fail(1, "unmapping guest code failed", e);
	}
	std::printf("cpu.selftest=PASS\n");
	return 0;
}

int run_apk(const char* path) {
	struct stat st {};
	ErrorText e;
	if (::stat(path, &st) != 0 || !S_ISREG(st.st_mode)) {
		return fail(3, "APK path is not a readable regular file", e);
	}
	const A32ApkLibrarySourceOptions opts{
		.max_virtual_path_bytes = 4096U, .max_archive_bytes = 1ULL << 30U,
		.max_entries = 65000U, .max_central_directory_bytes = 32ULL << 20U,
		.max_entry_name_bytes = 512U,
	};
	const A32ApkLibraryCatalogOptions cat{
		.max_libraries = 4096U, .max_soname_bytes = 128U,
		.max_total_soname_bytes = 64U * 1024U, .max_abi_directory_bytes = 64U,
	};
	A32ApkLibrarySource src{opts};
	const auto found = src.catalog(path, "lib/armeabi-v7a", cat);
	if (!found) {
		std::printf("apk.catalog.error=%d\n", int(found.error));
		return fail(3, "APK catalog rejected (ZIP layout or scan budget)", e);
	}
	if (found.sonames.empty()) {
		return fail(3, "no *.so under lib/armeabi-v7a", e);
	}
	std::printf("apk.catalog.count=%zu\n", found.sonames.size());
	for (const std::string& name : found.sonames) {
		std::printf("apk.catalog.lib=%s\n", name.c_str());
	}
	std::printf("apk.scan=PASS\n");
	return 0;
}

}  // namespace

int main(int argc, char** argv) {
	if (argc > 2 || (argc == 2 && (argv[1][0] == '\0' || argv[1][0] == '-'))) {
		std::printf("usage: pico_arm64_probe [PicoOnebot.apk]\nprobe.result=FAIL\n");
		std::printf("probe.exit_code=2\n");
		return 2;
	}
	const char* ok_name = liba32android_status_name(LIBA32ANDROID_STATUS_OK);
	std::printf("probe.api_version=%u\n", unsigned(liba32android_api_version()));
	if (liba32android_api_version() != LIBA32ANDROID_API_VERSION || !ok_name ||
		std::strcmp(ok_name, "OK") != 0) {
		std::printf("probe.result=FAIL\nprobe.failure=public API contract mismatch\n");
		return 1;
	}
	const int cpu_code = run_cpu();
	const bool scan_requested = argc == 2 && cpu_code == 0;
	const int code = scan_requested ? run_apk(argv[1]) : cpu_code;
	std::printf("probe.scope.synthetic_cpu_selftest=%s\n", cpu_code ? "FAIL" : "PASS");
	std::printf("probe.scope.readonly_apk_scan=%s\n",
				scan_requested ? (code ? "FAIL" : "PASS") : "NOT_RUN");
	std::printf("probe.scope.real_qq_launch=NOT_TESTED\n");
	std::printf("probe.result=%s\n", code ? "FAIL" : "PASS");
	return code;
}
