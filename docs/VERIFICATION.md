# Vérification de Hybrid Coach

## Résultat final du 16/10/2026 à 11:04 (Europe/Paris)

**45 tests sur 45 réussis**, aucun échec, aucune erreur, aucun test ignoré.

| Suite | Réussites |
|---|---:|
| Calculs sportifs et références | 18 |
| Récurrences | 7 |
| JSON, statistiques et confidentialité des exports | 10 |
| Room, réouverture et PDF avec Android local | 8 |
| Parcours Compose complets | 2 |
| Total | **45** |

La compilation de l’application et de l’APK de tests instrumentés a réussi. Android Lint termine avec **0 erreur, 20 avertissements et 3 informations** ; le rapport brut est conservé. Les avertissements concernent des versions plus récentes disponibles, des conventions d’API et des dépendances tierces. Ils n’ont pas été masqués.

Le test d’interface vérifie notamment une série **22 kg × 8**, la fin partielle puis sa présence dans le PDF réellement généré à partir du bouton de partage. Le sélecteur de partage et son autorisation de lecture sont vérifiés dans Android local ; l’envoi effectif par une application tierce sur un téléphone reste à contrôler.

Signature de l’APK vérifiée avec `apksigner` (schéma v2), paquet `fr.hybridcoach`, version `1.0.0`, Android 8.0 / API 26 minimum. Aucun accès Internet n’est déclaré dans l’APK.

Les contrôles PDF portent sur **24 pages** de notes longues et **2 pages** d’un parcours complet ; aucun mot hors des marges dans les deux fichiers. Captures du rendu local et des pages PDF dans `docs/screenshots/`.

Les rapports détaillés sont dans `livrables/rapports/`. Les essais sur téléphone physique, la vibration réelle et Android Studio Windows ne sont pas déclarés validés.

Ce document distingue le code livré, les tests effectivement exécutés et les vérifications qui nécessitent un téléphone. Le bilan final et les rapports sont fournis dans `livrables/`.

## Tests automatisés

Les tests JVM des calculs, récurrences, statistiques et exports couvrent notamment :

- Conversion vitesse/allure, temps d’un intervalle et objectif de course.
- Répétitions imbriquées, volumes calculables et protection contre une expansion excessive.
- Références VMA et allures, zones personnalisées et instantanés indépendants du profil.
- 1RM Epley, bornes de validation et virgule décimale.
- Récurrences inclusives, jours multiples, changement d’année, année bissextile et fréquence de deux semaines.
- Format JSON v1, aller-retour de sérialisation, Unicode et séances associées hors période.
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
