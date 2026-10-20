# Hybrid Coach 1.2.0 — Import Garmin Connect

L’étape 1 est implémentée dans le projet Obsidian Vault : connexion/MFA, import manuel, normalisation, rapprochement automatique ou validation, calendrier et statistiques communs, gestion des doublons, modifications et suppressions. La migration Room 2 → 3 conserve les données ; le plan et la date initiale d’une séance associée restent disponibles. Les jetons Garmin restent chiffrés sur le service ; la clé du service est chiffrée sur Android.

Validation : **81 tests Android/JVM et 11 tests Python réussis**, APK et tests instrumentés compilés, lint sans erreur. Image de service Python 3.13 construite et démarrée avec Podman ; API authentifiée et configuration Caddy validées.

**Configuration nécessaire :** déployer le service personnel HTTPS fourni, configurer son URL/clé dans Profil sportif → Garmin Connect, puis connecter le compte Garmin. Aucun serveur ni compte n’est préconfiguré. Le certificat public, la connexion Garmin réelle et le téléphone restent à valider sur les équipements configurés.

L’étape 2 n’envoie aucun entraînement : seul son contrat d’interface est prévu.

- APK : `HybridCoach-debug.apk`.
- Sources complètes : `HybridCoach-sources.zip`.
- Service : `HybridCoach-garmin-bridge.zip`.
- Guide : `docs/GARMIN.md`.
- Contrôles détaillés : `docs/VERIFICATION.md` et `livrables/rapports/`.
