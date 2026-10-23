# Hybrid Coach

Application Android native de planification et de suivi de course à pied et de musculation, avec données locales et fonctions utilisables hors ligne. La synchronisation Garmin facultative nécessite Internet. Interface française, thème sombre par défaut, thèmes clair et système disponibles.

## Nouveauté 1.6 — Interface allégée

Les écrans affichent d’abord l’essentiel : séances, dates et action principale. Les outils de planification sont regroupés dans **Outils** ; les réglages du profil, détails de préparation, filtres et champs facultatifs se déplient à la demande. Les données et fonctionnalités restent accessibles. [Repères de navigation](docs/INTERFACE.md).

## Nouveauté 1.5 — Réajustement rapide

Dans **Calendrier → Mode Planification → Outils → Réajuster rapidement**, décaler une sélection de séances, alléger une semaine ou changer la date de compétition. Vérifier les dates, volumes et conflits dans l’aperçu avant validation. Les résultats existants restent protégés. [Mode d’emploi](docs/PLANIFICATION.md#réajustement-rapide--version-15).

## Nouveauté 1.4 — Sauvegarde et transfert de téléphone

Dans **Profil sportif → Sauvegarde et transfert**, exporter un JSON complet puis le restaurer sur un autre téléphone. Le fichier inclut le carnet, les modèles, le profil et l’historique Garmin, avec contrôle d’intégrité et aperçu avant confirmation. La restauration remplace le carnet cible ; une copie locale du carnet précédent est conservée. Les clés de connexion ne sont pas transférées. [Mode d’emploi](docs/SAUVEGARDE.md).

## Correctif 1.3.1 — connexion Garmin

La demande de jeton accepte désormais les réponses texte et JSON au lieu d’imposer JSON. Les erreurs indiquent le code HTTP et l’étape précise (jeton, profil, activités ou détail), sans exposer les secrets. Ce correctif vise le HTTP 406 observé à la connexion ; sa résolution avec le compte réel reste à confirmer après une nouvelle tentative.

## Nouveauté 1.3 — Garmin sans serveur

Dans **Profil sportif → Garmin Connect**, ouvrir la page officielle pour créer une clé personnelle Garmin, coller cette clé et toucher **Connecter Garmin**, puis **Synchroniser Garmin**. Connexion HTTPS directe depuis Android, clé chiffrée sur le téléphone, aucun serveur ni mot de passe Garmin à configurer dans Hybrid Coach. L’import retrouve les séances prévues, demande confirmation en cas d’ambiguïté et évite les doublons. Les fonctions de calendrier et de statistiques utilisent les mêmes données locales. [Guide Garmin](docs/GARMIN.md).

## Nouveauté 1.1 — Planification avancée

**Calendrier → Mode Planification**, ou ouvrir une préparation puis **Ouvrir la planification avancée**. Quatre vues : jour, semaine, mois et préparation complète, avec ajout depuis une date, modèles, duplication/déplacement de séances, copie de semaines et glisser-déposer hebdomadaire.

Les préparations possèdent des sous-objectifs datés, superposables, affichés sur une frise. Une séance peut en travailler plusieurs. Les répétitions sont des arbres structurés disponibles aussi dans les autres sports : efforts, cibles, récupération active/passive, répétitions imbriquées, duplication et réorganisation. Les deux calendriers utilisent les mêmes données. Les exports PDF/JSON v2 incluent les périodes, les associations et les blocs.

La base Room est migrée de 1 à 2 sans effacer les séances ni les résultats. [Guide détaillé et migration](docs/PLANIFICATION.md).

## Ouvrir sous Windows 11

1. Installer Android Studio et le SDK Android 35 via **SDK Manager** (Android SDK Platform 35 et Build Tools 35.0.0).
2. Ouvrir **ce dossier**, celui contenant `settings.gradle.kts`, avec **File → Open**.
3. Dans **Settings → Build, Execution, Deployment → Build Tools → Gradle**, sélectionner un JDK **17** ou **21**. Ne pas utiliser Java 25 avec cette version de Gradle.
4. Laisser Android Studio créer `local.properties` avec le chemin de votre SDK. Le chemin Linux de cet environnement ne doit pas être réutilisé sur Windows.
5. Attendre la synchronisation Gradle, puis lancer la configuration **app**.

Les versions sont fixées : AGP 8.9.2, Gradle 8.11.1, Kotlin et compilateur Compose 2.1.20, BOM Compose 2025.04.01, Room 2.7.2, Navigation 2.9.0. Il s’agit d’une combinaison stable vérifiée ici, sans prétendre utiliser les versions les plus récentes. [Compatibilité AGP/Gradle/JDK](https://developer.android.com/build/releases/agp-8-9-0-release-notes), [configuration du compilateur Compose](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler), [versions de Room](https://developer.android.com/jetpack/androidx/releases/room).

## Compiler et installer

Dans PowerShell, à la racine :

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest lintDebug
```

APK produit : `app/build/outputs/apk/debug/app-debug.apk`. Une copie de livraison est fournie dans `livrables/HybridCoach-debug.apk` après vérification.

Sur Linux/macOS :

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

La première compilation nécessite Internet pour télécharger Gradle et les dépendances. Les fonctions locales n’ont besoin d’aucun accès réseau ; la connexion et la synchronisation Garmin utilisent Internet. Android **8.0 / API 26** minimum. La compilation et les tests automatisés ont été exécutés sur Linux ; l’ouverture dans Android Studio sous Windows n’a pas été exécutée dans cet environnement.

Pour un téléphone : activer le débogage USB, connecter le téléphone, le sélectionner dans Android Studio puis **Run**. Ou transférer l’APK et autoriser son installation depuis l’application qui l’ouvre. Une installation par-dessus une version existante signée avec la même clé conserve la base. La désinstallation efface les données locales ; exporter les préparations avant toute désinstallation. L’APK fourni utilise une signature debug, pas une clé de publication Play Store.

## Lancer un émulateur

Dans Android Studio : **Tools → Device Manager → Create Virtual Device**, choisir un téléphone (par exemple Pixel), télécharger une image **Android 15 / API 35**, puis démarrer l’appareil et cliquer sur **Run app**. Sur Windows, activer la virtualisation du processeur et le composant de virtualisation recommandé par Android Studio.

Les tests Android se lancent avec un émulateur ou un appareil connecté :

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Ils utilisent une base de test distincte pour les vérifications Room. Le test d’interface crée puis supprime une séance portant un nom de test dans l’application de l’appareil de test : utiliser un appareil/émulateur dédié.

## Parcours conseillé

1. Dans l’icône de réglages, renseigner la VMA, les références d’allure et éventuellement les zones. Les allures se saisissent en **secondes par kilomètre** : 3:30/km = 210. Les zones utilisent une ligne par zone, par exemple `1:120-140` en bpm ou `1:300-360` en secondes/km.
2. Dans **Prépas**, créer une préparation, une compétition et sa date. Le chrono cible se saisit en secondes : 34:48 = 2088. L’allure cible est calculée.
3. Planifier depuis la préparation, le calendrier ou le bouton **+**. Choisir le sport, les étapes de course ou les exercices. Les champs secondaires sont facultatifs.
4. Pour du fractionné, utiliser **Composer un fractionné** : effort, récupération, répétitions, blocs imbriqués, échauffement et retour au calme. Les étapes se modifient, se dupliquent et se réordonnent ensuite. Les récupérations sont placées uniquement entre les répétitions.
5. Pour un superset, attribuer le même nom de bloc aux exercices concernés. Ils sont regroupés pendant l’exécution.
6. Activer la récurrence hebdomadaire, choisir les jours et la date de fin. Une occurrence peut être modifiée seule ou avec les occurrences futures non commencées.
7. Ouvrir une séance et la démarrer. En musculation, saisir charges et répétitions puis cocher les séries ; le repos démarre automatiquement. En course, renseigner le résultat global et, au besoin, les intervalles.
8. Terminer en statut complet ou partiel. Retrouver le résultat dans **Historique**, et les graphiques dans **Progression**.
9. Dans la préparation, afficher toutes les semaines, associer une séance existante ou choisir **Partager la préparation**. Choisir PDF ou JSON, puis les performances et les notes à inclure.

## Fonctionnalités implémentées

- Accueil du jour, prochaines séances et reprise des séances en cours.
- Calendrier hebdomadaire avec plusieurs séances par jour, choix de date, création, modification, déplacement, duplication et suppression confirmée.
- Course, musculation, compétition, repos et autre sport ; six statuts, priorité, description, notes et association à une préparation.
- Récurrences hebdomadaires bornées par des dates ; modification d’une occurrence ou des suivantes non commencées ; duplication/répétition d’une semaine.
- Bibliothèque de plus de 70 exercices avec recherche, filtre musculaire et exercices personnels modifiables.
- Séances de force ordonnées : séries, répétitions/plages, charge, RIR, RPE, repos, tempo, notes, modes standard/unilatéral/poids du corps/lesté/assisté et blocs superset/biset/triset.
- Exécution avec performances précédentes, saisie par série, validation, ajout/retrait de séries, chrono et sauvegarde progressive. Repos : +30 s, passer, vibration.
- Course simple ou structurée : temps, distance ou fin manuelle ; blocs imbriqués ; récupération active/passive ; cibles allure/plage, vitesse, FC/zone, zone d’allure, puissance, cadence, RPE, % VMA et références de course.
- Profil sportif persistant dans Room (copié depuis l’ancien DataStore), conversions et objectifs calculés figés dans les séances planifiées.
- Résultats de course facultatifs : distance, durée, FC, dénivelé, RPE, fatigue, ressenti, notes et intervalles ; affichage du prévu/réalisé.
- Objectifs et préparations multiples : modification, archivage, semaines, compte à rebours, volumes prévus/réalisés, progression temporelle, résultats et séances restantes.
- Modèles réutilisables : création, modification, duplication, suppression et planification indépendante.
- Historique filtrable par sport, période et préparation ; résultats consultables et corrigeables.
- Progression : volume et durée de course, effort RPE déclaré, dernières performances, vue hybride, historique de force, records de charge/répétitions/volume, 1RM pertinent, séries et fréquence par muscle principal.
- Export PDF paginé et JSON versionné, avec retrait récursif des notes personnelles et exclusion optionnelle des performances ; partage Android natif avec autorisation de lecture du fichier.
- Base Room, clés étrangères, transactions, schéma exporté, aucune donnée de démonstration dans le carnet utilisateur.

Cette liste décrit les parcours codés. La couverture de vérification et les limites sont détaillées dans [docs/VERIFICATION.md](docs/VERIFICATION.md) ; aucune couverture exhaustive sur tous les téléphones n’est revendiquée.

## Limites de cette version

- Les résultats peuvent être consignés manuellement ou importés depuis Garmin avec une clé personnelle. Pas de GPS, guidage vocal, import Strava ni envoi de séances à la montre. La synchronisation Garmin est manuelle ; la connexion et l’import avec un compte réel restent à valider par l’utilisateur.
- Les durées/chronos/allures se saisissent en secondes, les distances de résultat en km et les étapes en mètres. Les répétitions chronométrées (gainage, par exemple) peuvent être décrites dans les répétitions/notes, mais la validation des séries de force enregistre un nombre entier de répétitions.
- Réorganisation des étapes par flèches et duplication des blocs. Le glisser-déposer est disponible pour déplacer les séances entre les jours visibles de la vue hebdomadaire. Un changement de parent d’une étape nécessite de la recréer dans l’autre bloc.
- Cinq niveaux de répétition, 10 000 étapes développées par séance, 50 séries par exercice ; une création récurrente couvre dix ans au maximum et une répétition de semaine 104 semaines au maximum. On peut planifier au-delà par opérations supplémentaires.
- Le repos reste précis dans l’écran actif. En arrière-plan, Android peut retarder l’alarme pour économiser la batterie ; après un redémarrage du téléphone, le compte à rebours est récupéré à la réouverture de la séance, sans alarme de démarrage système.
- Les zones sont définies manuellement. Les volumes prévus utilisent uniquement les distances calculables ; les plages d’allure utilisent leur milieu. La répartition RPE décrit l’effort déclaré, sans analyse physiologique automatique.
- Le volume de force utilise la charge saisie et ne reconstitue pas le poids du corps. Les muscles secondaires sont descriptifs ; les séries sont attribuées au muscle principal. Le 1RM n’est pas estimé pour les modes poids du corps/lesté/assisté.
- Le plan d’une séance commencée est verrouillé pour préserver son objectif initial. Les séries supplémentaires et les résultats restent modifiables ; dupliquer la séance permet de changer le plan.
- Les brouillons d’éditeur ne sont pas conservés après suppression du processus avant enregistrement. Les résultats saisis, eux, sont envoyés progressivement à Room ; attendre la fin de l’indicateur de sauvegarde avant un arrêt forcé.
- Le PDF intègre Liberation Sans ; les caractères non couverts (certains emoji ou alphabets) sont remplacés par `?`. Le JSON conserve l’Unicode original.
- Les sauvegardes JSON complètes permettent une restauration avec remplacement du carnet, sans fusion. Les exports PDF/JSON de préparation sont distincts et ne sont pas restaurables comme une sauvegarde complète. Le PDF présente les dates et semaines sous forme de liste.
- Base v4 : migrations additives testées depuis les v1, v2 et v3, schémas conservés et aucun effacement destructif automatique. Toute future évolution SQL devra fournir et tester une migration explicite.

## Organisation

- `data/` : Room, dépôt transactionnel, bibliothèque et reprise du profil depuis l’ancien DataStore.
- `backup/` : sauvegarde JSON versionnée, intégrité, aperçu et restauration transactionnelle.
- `domain/` : plans, résultats, calculs, récurrences et statistiques.
- `ui/` : ViewModel, navigation et écrans Compose.
- `export/` : document commun JSON/PDF et partage Android.
- `app/src/test/` : tests unitaires des calculs, récurrences, statistiques, plus tests Android et Compose avec Robolectric.
- `app/src/androidTest/` : tests Room, intégrité, reprise, PDF natif et parcours Compose.
- [Architecture, méthodes et migrations](docs/ARCHITECTURE.md).
- [Format JSON](docs/JSON_FORMAT.md).

Aucun serveur n’est nécessaire. Les fichiers du SDK, de Gradle, les caches, `local.properties` et les appareils de test ne font pas partie des sources à partager.

## Prochains ajouts

Overall:
-possibilité de création de lien calendar avec mise à jour en direct 

Course à pied:
-ajout d'un agent IA intégré qui avec un contexte est capable de t'accompagner dans la plannification d'une séance. (possibilité de lui faire ajouter directement des séances dans ton calendrier)
-possibilité de push directement des séances dans garmin connect (plus tard autres marques)
-récupération des séances réalisées dans strava et déduction de quel séance a été réalisée pour contabilisation dans kilométrage et statut "réalisée" d'une séance 

Musculation:
-suivi progression progressive overload

Projet intra-application
-Projet NN : déduction de la fc à un instant t à partir de toutes les données que l'on a en amont : prendre en compte l'évolution de la forme, le dénivelé ?, se base principalement sur l'allure.


