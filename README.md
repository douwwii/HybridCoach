# Hybrid Coach

Application Android native de planification et de suivi de course à pied et de musculation, entièrement locale et utilisable hors ligne. Interface française, thème sombre par défaut, thèmes clair et système disponibles.

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

La première compilation nécessite Internet pour télécharger Gradle et les dépendances. L’application, une fois installée, n’a besoin d’aucun accès réseau. Android **8.0 / API 26** minimum. La compilation et les tests automatisés ont été exécutés sur Linux ; l’ouverture dans Android Studio sous Windows n’a pas été exécutée dans cet environnement.

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
4. Pour du fractionné, ajouter une étape **Répétitions**, puis ajouter ses enfants. Les blocs peuvent contenir d’autres blocs. Les flèches changent l’ordre à l’intérieur du même niveau.
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
- Profil sportif dans DataStore, conversions et objectifs calculés figés dans les séances planifiées.
- Résultats de course facultatifs : distance, durée, FC, dénivelé, RPE, fatigue, ressenti, notes et intervalles ; affichage du prévu/réalisé.
- Objectifs et préparations multiples : modification, archivage, semaines, compte à rebours, volumes prévus/réalisés, progression temporelle, résultats et séances restantes.
- Modèles réutilisables : création, modification, duplication, suppression et planification indépendante.
- Historique filtrable par sport, période et préparation ; résultats consultables et corrigeables.
- Progression : volume et durée de course, effort RPE déclaré, dernières performances, vue hybride, historique de force, records de charge/répétitions/volume, 1RM pertinent, séries et fréquence par muscle principal.
- Export PDF paginé et JSON versionné, avec retrait récursif des notes personnelles et exclusion optionnelle des performances ; partage Android natif avec autorisation de lecture du fichier.
- Base Room, clés étrangères, transactions, schéma exporté, aucune donnée de démonstration dans le carnet utilisateur.

Cette liste décrit les parcours codés. La couverture de vérification et les limites sont détaillées dans [docs/VERIFICATION.md](docs/VERIFICATION.md) ; aucune couverture exhaustive sur tous les téléphones n’est revendiquée.

## Limites de cette version

- La course est planifiée puis consignée manuellement : pas de GPS, guidage vocal, import Garmin/Strava, capteurs, synchronisation ni compte. Ces intégrations n’étaient pas nécessaires à cette version.
- Les durées/chronos/allures se saisissent en secondes, les distances de résultat en km et les étapes en mètres. Les répétitions chronométrées (gainage, par exemple) peuvent être décrites dans les répétitions/notes, mais la validation des séries de force enregistre un nombre entier de répétitions.
- Réorganisation par flèches, sans glisser-déposer. Un changement de parent d’une étape nécessite de la recréer dans l’autre bloc.
- Cinq niveaux de répétition, 10 000 étapes développées par séance, 50 séries par exercice ; une création récurrente couvre dix ans au maximum et une répétition de semaine 104 semaines au maximum. On peut planifier au-delà par opérations supplémentaires.
- Le repos reste précis dans l’écran actif. En arrière-plan, Android peut retarder l’alarme pour économiser la batterie ; après un redémarrage du téléphone, le compte à rebours est récupéré à la réouverture de la séance, sans alarme de démarrage système.
- Les zones sont définies manuellement. Les volumes prévus utilisent uniquement les distances calculables ; les plages d’allure utilisent leur milieu. La répartition RPE décrit l’effort déclaré, sans analyse physiologique automatique.
- Le volume de force utilise la charge saisie et ne reconstitue pas le poids du corps. Les muscles secondaires sont descriptifs ; les séries sont attribuées au muscle principal. Le 1RM n’est pas estimé pour les modes poids du corps/lesté/assisté.
- Le plan d’une séance commencée est verrouillé pour préserver son objectif initial. Les séries supplémentaires et les résultats restent modifiables ; dupliquer la séance permet de changer le plan.
- Les brouillons d’éditeur ne sont pas conservés après suppression du processus avant enregistrement. Les résultats saisis, eux, sont envoyés progressivement à Room ; attendre la fin de l’indicateur de sauvegarde avant un arrêt forcé.
- Le PDF intègre Liberation Sans ; les caractères non couverts (certains emoji ou alphabets) sont remplacés par `?`. Le JSON conserve l’Unicode original.
- Export de préparation, sans import ni restauration globale de la base dans l’interface. Le PDF présente les dates et semaines sous forme de liste, sans grille mensuelle ni graphiques vectoriels élaborés.
- Base initiale v1 : schéma conservé et aucun effacement destructif automatique. Toute future évolution SQL devra fournir et tester une migration explicite.

## Organisation

- `data/` : Room, dépôt transactionnel, bibliothèque et DataStore.
- `domain/` : plans, résultats, calculs, récurrences et statistiques.
- `ui/` : ViewModel, navigation et écrans Compose.
- `export/` : document commun JSON/PDF et partage Android.
- `app/src/test/` : tests unitaires des calculs, récurrences, statistiques, plus tests Android et Compose avec Robolectric.
- `app/src/androidTest/` : tests Room, intégrité, reprise, PDF natif et parcours Compose.
- [Architecture, méthodes et migrations](docs/ARCHITECTURE.md).
- [Format JSON](docs/JSON_FORMAT.md).

Aucun serveur n’est nécessaire. Les fichiers du SDK, de Gradle, les caches, `local.properties` et les appareils de test ne font pas partie des sources à partager.
