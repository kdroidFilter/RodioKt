# RodioKt

Kotlin Multiplatform Library

### Run Sample App

 - Desktop JVM: `./gradlew :sample:composeApp:run`
- If your network uses TLS inspection or a custom root CA, add that CA with `RodioHttp.addRootCertPem(...)`.
  For quick debugging only, you can disable validation with `RodioHttp.setAllowInvalidCerts(true)`.

### Publish to MavenLocal

1) Run `./gradlew :rodio:publishToMavenLocal`
2) Open `~/.m2/repository/io/github/kdroidfilter/rodio/`

### Publish to MavenCentral

1) Create an account and a namespace on Sonatype:  
   https://central.sonatype.org/register/central-portal/#create-an-account
2) Add developer id, name, email and the project url to  
   `./rodio/build.gradle.kts`
3) Generate a GPG key:  
   https://getstream.io/blog/publishing-libraries-to-mavencentral-2021/#generating-a-gpg-key-pair
   ```
   gpg --full-gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys XXXXXXXX
   gpg --export-secret-key XXXXXXXX > XXXXXXXX.gpg
   ```
4) Add these lines to `gradle.properties`:
   ```
   signing.keyId=XXXXXXXX
   signing.password=[key password]
   signing.secretKeyRingFile=../XXXXXXXX.gpg
   mavenCentralUsername=[generated username]
   mavenCentralPassword=[generated password]
   ```
5) Run `./gradlew :rodio:publishAndReleaseToMavenCentral --no-configuration-cache`
