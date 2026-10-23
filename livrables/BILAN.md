# Hybrid Coach 1.4.0 — Sauvegarde JSON et transfert de téléphone

Dans **Profil sportif → Sauvegarde et transfert**, exporter un JSON complet, transférer le fichier sur un autre téléphone, puis le sélectionner avec **Restaurer un fichier JSON**. Un aperçu daté affiche le contenu avant confirmation. La restauration remplace le carnet et le profil ; elle ne fusionne pas deux carnets.

Le fichier inclut les séances et performances, séries et saisies en cours, modèles, exercices, objectifs, préparations, sous-objectifs, plans imbriqués, notes, profil sportif, thème et historique/associations Garmin. Les identifiants restent stables. Les clés de connexion Garmin et le Keystore sont exclus ; reconnecter Garmin sur le nouveau téléphone. Le JSON contient les données personnelles en clair.

Avant remplacement, une copie privée du carnet précédent est conservée et reste exportable. L’import est transactionnel : un fichier invalide, une impossibilité d’écrire la copie de sécurité ou un échec SQL n’entraîne pas de remplacement partiel. Le format est versionné et contrôlé par SHA-256. La base v4 ajoute le profil sportif ; les migrations depuis v1/v2/v3 sont couvertes et l’ancien DataStore est repris sans effacement.

Validation : **125 tests Android/JVM réussis**, APK et tests instrumentés compilés, lint avec **0 erreur, 27 avertissements et 3 informations**. Signature debug vérifiée et identique à la précédente livraison. Les tests couvrent le transfert entre bases et le parcours de fichiers local ; la migration physique entre deux téléphones reste à vérifier. Aucun appareil ADB n’était connecté pour installer la nouvelle version dans cette livraison.

- APK **1.4.0 / code 6** : `HybridCoach-debug.apk`.
- Sources actualisées : `HybridCoach-sources.zip`.
- Guide : `docs/SAUVEGARDE.md`.
- Vérifications : `docs/VERIFICATION.md` et `livrables/rapports/`.
- L’ancien service Garmin 1.2 reste archivé ; il n’est pas nécessaire à l’application actuelle.

Installer cette version par-dessus l’ancienne pour conserver le carnet local, puis exporter la sauvegarde avant tout changement de téléphone ou désinstallation. La clé de signature actuelle est conservée ; une nouvelle clé de publication n’a pas été créée dans cette évolution.
