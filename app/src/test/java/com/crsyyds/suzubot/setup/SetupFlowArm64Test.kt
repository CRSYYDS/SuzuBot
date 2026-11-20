package com.crsyyds.suzubot.setup

import android.app.Application
import android.content.Context
import android.content.Intent
import com.crsyyds.suzubot.pico.PicoOnebotClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SetupFlowArm64Test {

	private lateinit var app: Application

	@Before
	fun setUp() {
		app = RuntimeEnvironment.getApplication()
		ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a"))
	}

	@Test
	fun `纯 ARM64 设备跳过协议端安装步骤`() = runBlocking {
		val flow = SetupFlow(app)
		assertEquals(SetupFlow.Outcome.Done(SetupFlow.Step.START_PROTOCOL), flow.runCurrent())
		assertEquals(SetupFlow.Step.START_PROTOCOL, flow.currentStep)
		assertNull(shadowOf(app).nextStartedActivity)
	}

	@Test
	fun `旧原生部署记录不会跳过外部部署`() = runBlocking {
		app.getSharedPreferences("setup", Context.MODE_PRIVATE).edit()
			.putInt("step", SetupFlow.Step.DONE.order)
			.putBoolean("passed_DONE", true)
			.commit()
		val flow = SetupFlow(app)
		assertFalse(flow.isCompleted())
		assertEquals(SetupFlow.Step.DEVICE_CHECK, flow.currentStep)
		assertEquals(SetupFlow.Outcome.Done(SetupFlow.Step.START_PROTOCOL), flow.runCurrent())
		assertNull(shadowOf(app).nextStartedActivity)
	}

	@Test
	fun `外部模式登录入口打开本机网页控制台`() {
		assertTrue(PrivacyHelper.bringPicoToFrontForLogin(app))
		val intent = shadowOf(app).nextStartedActivity
		assertEquals(Intent.ACTION_VIEW, intent.action)
		assertEquals(PicoOnebotClient.DEFAULT_BASE_URL, intent.dataString)
		assertNull(intent.component)
	}
}
