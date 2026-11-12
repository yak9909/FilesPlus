extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.filesplus.extension"
}

dependencies {
    // SMB2/3 client (NTLM only). Kerberos/GSS classes are never loaded on Android.
    implementation(libs.smbj)
    implementation(libs.bcprov)
}
