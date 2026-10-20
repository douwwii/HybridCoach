# Vérification de Hybrid Coach

## Résultat final du 19/10/2026 à 17:55 (Europe/Paris) — version 1.2.0

**81 tests Android/JVM sur 81 réussis**, aucun échec, aucune erreur, aucun test ignoré. **11 tests Python sur 11 réussis**.

| Couverture | Réussites |
|---|---:|
| Calculs sportifs, récurrences, statistiques, répétitions, exports | 41 |
| Room, persistance, préparation et PDF locaux existants | 15 |
| Parcours Compose existants | 4 |
| Classement et validation des paramètres Garmin | 7 |
| Import Garmin, doublons, suppressions, modifications, migrations | 13 |
| Parcours Compose de confirmation et provenance Garmin | 1 |
| Total Android/JVM | **81** |
| Service Python : authentification, MFA, cache, pagination, erreurs, adaptateur | **11** |

Contrôles exécutés depuis `Obsidian Vault/My life/Pro/Projets/HybridCoach` : `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` avec JBR 21. Résultat : `BUILD SUCCESSFUL`. Lint : **0 erreur, 25 avertissements et 3 informations**. Les avertissements ne sont pas masqués ; ils concernent notamment les versions de dépendances, des conventions Compose, la configuration de sauvegarde et du code tiers PDFBox/Bouncy Castle.

La migration réelle **2 → 3** conserve les séances, les plans, les périodes et les associations aux sous-objectifs dans un fichier SQLite créé à partir du schéma v2. La migration **1 → 2 → 3** reste couverte par le test existant. Le rapprochement teste notamment 11 km réalisés pour 12 km prévus, les cas ambigus, deux activités réclamant une seule séance, les séances terminées manuellement et les sessions déjà liées.

Les tests d’import vérifient l’absence de doublons, les modifications distantes, les ignorés, les changements de sport, la suppression distante, la dissociation et la restauration des mesures locales, ainsi que le kilométrage prévu dans sa semaine d’origine et le réel à sa date effective. Le parcours Compose confirme une activité ambiguë, ouvre sa séance et vérifie la provenance Garmin. Les contrôles de navigation sont désactivés tant que le graphe et les données ne sont pas chargés, pour éviter les clics prématurés au démarrage. Capture : `docs/screenshots/garmin-session.png`.

Le service a été installé/testé localement avec Python 3.14 ; son image **Python 3.13** a été construite avec Podman, puis démarrée sous l’utilisateur non root configuré. Les routes HTTP de santé et d’état ont été vérifiées, avec refus d’un accès non authentifié. La configuration du proxy Caddy 2.10.2 est validée. Les tests Python utilisent le SDK réel pour vérifier le contrat de l’adaptateur, et des réponses simulées pour les échanges Garmin. Un avertissement de dépréciation `TestClient/httpx` est conservé dans le rapport Python ; il n’empêche pas les tests.

**À configurer et valider sur les équipements réels :** service HTTPS déployé avec votre domaine et votre certificat, connexion à votre compte Garmin (dont MFA), import depuis Garmin réel et usage sur téléphone. Aucun compte ou hébergement n’a été fourni et aucun entraînement n’a été envoyé vers Garmin. Le code de l’étape 2 se limite au contrat abstrait `GarminWorkoutExporter`.

L’APK est en version **1.2.0 / code 3**, Android 8.0 / API 26 minimum, cible 35. Il déclare désormais `INTERNET` pour la synchronisation explicitement demandée. Les fonctions locales restent disponibles hors ligne. Les tests instrumentés ont été compilés, sans exécution sur téléphone ou émulateur complet.

Les contrôles PDF existants restent validés : documents de résistance et de parcours lus par un lecteur indépendant. Les fichiers de contrôle et captures utilisent exclusivement des données synthétiques. Les rapports sont conservés dans `livrables/rapports/`.

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
