# payload.cpp looks up this class and its main() by name via DexClassLoader/JNI
-keep class jb.companion.EvilPMS {
    public static void main(java.lang.String[]);
}
