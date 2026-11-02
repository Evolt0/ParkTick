# JNI entry names are deliberately fixed; business classes remain optimizable.
-keep class com.evolto.parktick.recognition.NativeBridge { *; }
-keep class com.evolto.parktick.recognition.PlateCandidate { public <init>(...); }
