-keep class com.ikev2client.** { *; }
-keep class org.strongswan.android.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
