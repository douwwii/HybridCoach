# Vérification de Hybrid Coach

## Résultat final du 16/10/2026 à 17:11 (Europe/Paris) — version 1.1.0

**60 tests sur 60 réussis**, aucun échec, aucune erreur, aucun test ignoré.

| Suite | Réussites |
|---|---:|
| Calculs sportifs et références | 18 |
| Récurrences | 7 |
| JSON, statistiques et confidentialité des exports | 10 |
| Room, réouverture et PDF avec Android local | 8 |
| Parcours Compose existants | 2 |
| Répétitions, récupérations, copies et export v2 | 6 |
| Planification : migration réelle v1 → v2, liens, périodes, copies, PDF | 7 |
| Parcours Compose de planification et fractionné imbriqué | 2 |
| Total | **60** |

Contrôles exécutés depuis le dossier `Obsidian Vault/My life/Pro/Projets/HybridCoach`.

Commande exécutée : `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` avec JBR 21. Compilation et contrôles terminés avec `BUILD SUCCESSFUL`. Android Lint : **0 erreur, 24 avertissements et 3 informations**. Les avertissements concernent les versions de dépendances, une convention de paramètre Compose, la configuration de sauvegarde Android et du code de certificats tiers dans PDFBox/Bouncy Castle ; les informations suggèrent des helpers KTX ou des états entiers spécialisés. Aucun avertissement n’est masqué.

Les nouveaux tests d’interface exécutent la création d’un fractionné de 3 × (4 × 400 m) dans **Autre sport**, le passage entre les quatre vues, la création d’un sous-objectif et un véritable geste de glisser-déposer vers le lendemain. Le calendrier classique retrouve la séance à sa nouvelle date. Les tests existants couvrent toujours la reprise, la saisie de force, la fin partielle et le partage PDF.

La migration est vérifiée depuis un fichier SQLite créé à partir du schéma Room v1 : objectif, préparation, séance terminée, résultat, modèle et exercice restent présents après l’ouverture en v2. Les périodes superposées, les associations multiples, leur nettoyage et la conservation des liens lors des copies sont testés avec fermeture/réouverture de la base.

Signature de l’APK vérifiée avec `apksigner` (schéma v2), paquet `fr.hybridcoach`, version **1.1.0 / code 2**, Android 8.0 / API 26 minimum, cible 35. Aucun accès Internet n’est déclaré dans l’APK.

Les PDF générés par le moteur applicatif sont lus par Apache PDFBox JVM. Le nouveau PDF de planification de **3 pages** affiche les sous-objectifs, leurs périodes et les récupérations entre répétitions. Ses pages ont été rendues avec Poppler et inspectées. Les contrôles des coordonnées des mots sur ce fichier, le test de résistance de **24 pages** et le parcours de **2 pages** ne trouvent aucun débordement. Captures dans `docs/screenshots/` ; données exclusivement synthétiques de test.

Les rapports détaillés sont dans `livrables/rapports/`. Les tests instrumentés ont été compilés ; ils n’ont pas été exécutés sur téléphone ou émulateur complet pendant cette validation. La vibration réelle, le comportement de l’alarme en arrière-plan et Android Studio Windows restent à contrôler sur leurs environnements respectifs.

## Tests automatisés

Les tests JVM des calculs, récurrences, statistiques et exports couvrent notamment :

- Conversion vitesse/allure, temps d’un intervalle et objectif de course.
- Répétitions imbriquées, volumes calculables et protection contre une expansion excessive.
- Références VMA et allures, zones personnalisées et instantanés indépendants du profil.
- 1RM Epley, bornes de validation et virgule décimale.
- Récurrences inclusives, jours multiples, changement d’année, année bissextile et fréquence de deux semaines.
- Formats JSON v1/v2, aller-retour de sérialisation, Unicode et séances associées hors période.
- Exclusion récursive des notes personnelles et exclusion des performances.
- Statistiques fondées sur les statuts terminée/partielle et les séries cochées.

Les tests Android locaux (`RepositoryLocalAndroidTest`, exécutés avec Robolectric) utilisent des fichiers Room réels dans le système de fichiers isolé du test :

- Fermeture puis réouverture de la base avec une séance en cours et ses séries.
- Validation partielle et fin du repos enregistré.
- Modification des récurrences futures sans réécrire les séances terminées ou en cours.
- Copies de semaines indépendantes et sans résultats.
- Modification d’un modèle sans changement de l’instantané planifié.
- Suppression en cascade du résultat et refus des associations orphelines.
- Refus de réécrire le plan d’une séance terminée.
- PDF effectivement généré par le moteur de l’application, puis lu par Apache PDFBox JVM et accessible par l’URI FileProvider.

Les tests Compose locaux (`PlannerLocalAndroidUiTest`) exercent la création d’une course et sa reprise, ainsi que le parcours préparation → musculation → saisie d’une série → fin partielle → partage. Leur résultat final est indiqué dans le bilan de livraison ; la simple présence du code de test n’équivaut pas à une réussite.

## Contrôle des exports PDF

Un document de résistance comportant une note très longue produit 24 pages. La lecture indépendante retrouve le titre, les accents et la synthèse finale. Les premières et dernières pages ont été rendues avec Poppler et inspectées visuellement. Une analyse des coordonnées de tous les mots des 24 pages n’a trouvé aucun mot hors des marges.

Les fichiers dans `docs/screenshots/` sont exclusivement des artefacts de test et de contrôle. Ils ne sont jamais chargés dans la base de l’application installée. Le PDF `export-test.pdf` contient des données synthétiques explicitement nommées « Test export » ; ce fichier ne représente aucun résultat sportif de l’utilisateur.

## Portée de validation

| Domaine | Vérification disponible |
|---|---|
| Projet Android, ressources, Room/KSP, dépendances | Compilation de l’APK debug et des tests instrumentés |
| Calculs, récurrences, JSON et méthodes statistiques | Tests JVM |
| Room, intégrité, transactions et réouverture | Tests Android locaux Robolectric |
| Mise en page initiale et formulaire de séance | Captures du rendu Android local |
| Génération, texte, pagination et lecture PDF | Moteur applicatif + lecteur indépendant + Poppler |
| Navigation, saisie et partage | Tests Compose locaux, résultat dans le bilan final |
| Téléphone physique, vibration, économie d’énergie | À vérifier sur appareil |
| Émulateur Android complet | Non exécuté jusqu’au bout : les versions essayées ont quitté avec le code 139 avant une connexion ADB utilisable |
| Android Studio sous Windows 11 | Instructions fournies ; non exécuté ici |
| Toutes tailles d’écran et toutes versions Android | Couverture non exhaustive |

Les tests instrumentés dans `app/src/androidTest` restent disponibles pour un émulateur fonctionnel ou un appareil dédié. Ils n’ont pas été déclarés réussis dans cet environnement.

## Contrôle manuel conseillé sur le téléphone

1. Installer l’APK et créer une préparation avec compétition, distance et chrono.
2. Planifier une course structurée et une séance de force, puis une récurrence hebdomadaire.
3. Démarrer la force, saisir une série, quitter l’application et la rouvrir ; retrouver la série et le chrono.
4. Vérifier la vibration de fin de repos, +30 secondes et le bouton pour passer le repos.
5. Terminer la séance, consigner une sortie de course puis consulter l’historique et la progression.
6. Partager un PDF dans une application installée et l’ouvrir sur un autre appareil.
7. Vérifier l’apparence claire/sombre et la lisibilité avec la taille de police habituelle.

Ce contrôle complémentaire concerne les interactions réelles avec Android et les autres applications ; les résultats sportifs restent locaux et aucun compte n’est nécessaire.
