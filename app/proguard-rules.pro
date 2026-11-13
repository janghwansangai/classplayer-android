# youtubedl-android는 JSON 역직렬화에 리플렉션을 쓴다
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.youtubedl_common.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
