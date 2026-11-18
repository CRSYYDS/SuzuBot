# 保持 org.json 反射相关不被裁掉（我们大量用 JSONObject）
-keep class org.json.** { *; }

# 保留崩溃栈可读性
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
