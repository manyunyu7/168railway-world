# Rust exports static JNI symbols for the Studio engine and its Android panels.
-keepclasseswithmembernames,includedescriptorclasses class world.nativegame.** {
    native <methods>;
}
