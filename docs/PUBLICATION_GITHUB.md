# Publier Hybrid Coach sur GitHub

Dépôt : **douwwii/HybridCoach**. Les publications stables complètes alimentent l’écran de mise à jour. Les APK et archives sont des assets de Releases ; les sources, guides, captures utiles et scripts restent dans Git. Les anciens livrables locaux restent sur disque, mais les nouveaux fichiers générés ne sont plus suivis dans le dépôt.

## Signature : configuration unique

La distribution GitHub conserve le certificat des APK déjà installés :

`29638862d8945c1bdc16872ea4000c520e8badfc36455217212077b6bdf441e0`

L’APK de publication utilise le build **release non débogable**, signé avec cette clé existante. Son certificat provient de la signature debug historique ; ce choix conserve la mise à jour sans désinstallation. Ce n’est pas une nouvelle clé de publication Play Store. La copie privée stable est conservée hors du dépôt dans `~/.config/hybridcoach/signing/distribution.keystore` sur la machine de préparation. Ne jamais publier cette clé, un mot de passe, un jeton ou un fichier de configuration privé.

`release-config.json` contient uniquement le dépôt, l’identifiant et l’empreinte **publique** du certificat. Changer cette empreinte ne rend pas une nouvelle clé compatible avec les anciennes installations.

Pour la compilation locale, définir :

- `HYBRIDCOACH_KEYSTORE` : chemin absolu du keystore privé existant.
- `HYBRIDCOACH_STORE_PASSWORD` : mot de passe du keystore.
- `HYBRIDCOACH_KEY_ALIAS` : alias de la clé.
- `HYBRIDCOACH_KEY_PASSWORD` : mot de passe de la clé.

Ne pas les inscrire dans Gradle, `.env` suivi ou le terminal enregistré en vidéo. Aucun remplacement automatique de clé n’est autorisé : une configuration absente fait échouer la compilation ou la préparation.

## Publication automatique avec GitHub Actions

Dans **Settings → Secrets and variables → Actions**, configurer une seule fois :

| Secret | Valeur |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Keystore existant encodé en base64 |
| `RELEASE_STORE_PASSWORD` | Mot de passe du keystore |
| `RELEASE_KEY_ALIAS` | Alias de la clé |
| `RELEASE_KEY_PASSWORD` | Mot de passe de la clé |

Cette configuration transmet la clé à GitHub pour permettre la signature sur ses runners. Elle doit être autorisée par le propriétaire et réservée aux collaborateurs de confiance. Les secrets ne sont ni committés ni placés dans les artefacts ; le workflow supprime sa copie temporaire. Le workflow des pull requests ne reçoit pas ces secrets.

Pour chaque nouvelle version :

1. Augmenter `versionName` **et** `versionCode` dans `app/build.gradle.kts`. Utiliser un nom `X.Y.Z`, par exemple `1.7.1`. Le code Android doit toujours augmenter.
2. Ajouter une section correspondante dans `CHANGELOG.md` et vérifier les tests.
3. Committer et pousser les sources.
4. Créer et pousser un tag correspondant, par exemple `v1.7.1`.
5. Le workflow **Préparer une publication** teste le projet, compile un APK release signé, vérifie son certificat et crée un **brouillon** contenant tous les fichiers.
6. Relire le brouillon dans Releases, puis cliquer **Publish release**. La version devient détectable dans l’application.

Un tag ne doit jamais être réutilisé pour des binaires différents. Une publication partiellement téléversée reste en brouillon et n’est donc pas proposée par l’application. Les nouvelles versions restent une décision du mainteneur : aucun changement du code ne déclenche seul une publication publique.

Les actions externes sont fixées par SHA de commit. Le workflow de vérification ne possède que `contents: read` ; le droit `contents: write` n’existe que dans le job de publication. Les rapports des tests/lint sont conservés en artefacts GitHub pendant 14 jours.

## Préparer et publier depuis un ordinateur

Après configuration de la signature et validation des tests :

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
python3 -m unittest discover -s scripts/tests -v
```

Enregistrer les sources et les documents vérifiés dans Git, puis :

```bash
python3 scripts/prepare_release.py
```

Le dépôt doit être propre ; les sorties vont dans `dist/vX.Y.Z/`, ignoré par Git. Le script refuse un APK debug, non signé, signé avec une autre clé, périmé ou sans notes de version. Il vérifie aussi que l’archive source ne contient pas de keystore ni de configuration locale.

Le dossier contient :

| Fichier | Rôle |
|---|---|
| `HybridCoach-X.Y.Z.apk` | APK installable signé |
| `update.json` | Métadonnées lues par l’application |
| `release-notes.md` | Nouveautés et informations de publication |
| `INSTALLATION.md` | Instructions pour les utilisateurs |
| `SHA256SUMS` | Empreintes des fichiers distribués |
| `apk-signature.txt` | Rapport du certificat public |
| `HybridCoach-sources.zip` | Sources exactes du commit indiqué |

Une authentification GitHub disposant de l’accès en écriture au dépôt permet de créer le brouillon :

```bash
python3 scripts/publish_release.py dist/vX.Y.Z
```

Ce script lit `GH_TOKEN` ou `GITHUB_TOKEN` depuis l’environnement. Ne pas mettre le jeton dans une commande enregistrée ou le chat. Avec GitHub Actions, le jeton du job est fourni automatiquement. L’option `--publish` publie seulement après le téléversement complet et la vérification de tous les fichiers. Le script ne remplace jamais une publication déjà publique.

Sans authentification en ligne de commande, créer une release dans l’interface GitHub, choisir le tag exact, copier `release-notes.md` et joindre **tous les fichiers** du dossier. Publier après le téléversement. Le nom et l’URL de l’APK doivent rester ceux inscrits dans `update.json`.

## Contrat utilisé par l’application

La recherche utilise `GET https://api.github.com/repos/douwwii/HybridCoach/releases/latest`. La réponse doit être une release stable publique avec un unique `update.json` et l’APK correspondant. La comparaison utilise le code Android numérique, sans comparer lexicalement `1.10` et `1.9`.

Le manifeste porte `format: fr.hybridcoach.update`, `schemaVersion: 1`, l’identifiant de l’app, les versions, les API Android, le certificat public, le nom/lien/taille/SHA-256 de l’APK, les nouveautés et le commit source. La date affichée provient de `published_at` sur GitHub. Les URL initiales sont limitées au dépôt ; les redirections HTTPS sont limitées aux domaines GitHub nécessaires. Le client applique des délais réseau et des tailles maximales, puis contrôle à nouveau le fichier avant de lancer l’installateur Android.
