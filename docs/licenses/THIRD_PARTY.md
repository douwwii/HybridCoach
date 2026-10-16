# Composants tiers

Les dépendances AndroidX, Kotlin, Coroutines, Serialization et PDFBox-Android sont distribuées selon leurs licences respectives (principalement Apache 2.0). Les notices de dépendances restent celles de leurs distributions.

- PDFBox-Android 2.0.27.0 : https://github.com/TomRoush/PdfBox-Android — Apache 2.0. Utilisé pour générer les documents PDF localement.
- Liberation Sans : police intégrée au PDF pour assurer le rendu des caractères français. Les fichiers de police sont fournis avec la licence complète dans `LiberationSans.txt` (SIL Open Font License).
- Gradle Wrapper : https://github.com/gradle/gradle — Apache 2.0.
- Robolectric et Apache PDFBox JVM : dépendances de test uniquement, non incluses dans l’application.

Le PDF remplace par `?` les caractères sans glyphe dans la police intégrée, notamment certains emoji. Le JSON conserve les caractères Unicode d’origine.
