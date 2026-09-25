plugins {
    id("com.android.application")
}

android {
    namespace = "com.deltawaken.adbtiles"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deltawaken.adbtiles"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signé avec la clé de debug : cette app est distribuée à la main, pas sur Play.
            signingConfig = signingConfigs.getByName("debug")
        }
        // Variante de BANC : identique à release, SANS BootReceiver ni RECEIVE_BOOT_COMPLETED
        // (src/noboot/AndroidManifest.xml). Sert à isoler le chemin de la tuile : si le port se
        // rouvre après un redémarrage avec cette build, c'est la liaison de SystemUI qui l'a fait,
        // et rien d'autre. Même applicationId, même signature : s'installe par-dessus.
        create("noboot") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
