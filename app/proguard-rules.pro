# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep model-related classes
-keep class com.example.mytts.engine.** { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
