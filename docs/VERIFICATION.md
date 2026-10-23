# Vérification de Hybrid Coach

## Résultat du 23/10/2026 — version 1.6.0, interface allégée

**145 tests Android/JVM sur 145 réussis**, aucun échec, aucune erreur, aucun test ignoré.

| Couverture | Réussites |
|---|---:|
| Calculs sportifs, récurrences, statistiques, répétitions, exports | 41 |
| Room, persistance, préparation et PDF locaux existants | 15 |
| Parcours Compose existants | 4 |
| Classement Garmin et validation de clé personnelle | 7 |
| Import Garmin, doublons, suppressions, modifications, migrations | 16 |
| API personnelle : contrat HTTP, formats de jeton, normalisation, pagination, renouvellement, erreurs et comptes | 18 |
| Transport HTTPS : en-têtes effectifs, POST vide, erreurs brutes exclues | 2 |
| Chiffrement local, rotation, déconnexion et configuration corrompue | 2 |
| Parcours Compose Garmin : connexion directe, recréation, association et détail | 2 |
| Sauvegarde : format, intégrité, Unicode, brouillons, références et versions | 6 |
| Transfert, transactions, reprise, copie de sécurité, migration v3 et profil | 10 |
| Parcours Compose : sélecteurs de fichiers, aperçu, confirmation, annulation et restauration | 2 |
| Réajustement : dates, sélection, conflits, protections, phases et blocs imbriqués | 10 |
| Réajustement Room : persistance, aperçus périmés et annulation transactionnelle | 5 |
| Réajustement Compose : annuler, appliquer, allègement, sélection vide et calendrier classique | 2 |
| Présentation progressive : champs repliés, sauvegarde et accès aux outils/filtres | 3 |
| Total Android/JVM | **145** |

Contrôles exécutés depuis `Obsidian Vault/My life/Pro/Projets/HybridCoach` :

```bash
JAVA_HOME=/home/douwwii/.jdks/jbr-21.0.11 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Résultat : **BUILD SUCCESSFUL**. Lint : **0 erreur, 27 avertissements et 3 informations**, sans suppression des avertissements. Ils concernent les versions de dépendances, des conventions Compose/KTX, la sauvegarde et du code tiers PDFBox/Bouncy Castle. Les préférences chiffrées utilisent volontairement `commit()` pour vérifier l’écriture effective avant d’annoncer la connexion/déconnexion.

L’APK est en version **1.6.0 / code 8**, Android 8.0 / API 26 minimum, cible 35. La signature debug est vérifiée et identique à celle du précédent APK livré, pour permettre une mise à jour sur place. Les tests instrumentés sont compilés ; ils n’ont pas été exécutés sur un téléphone ou un émulateur complet.

Contrôle historique de la version 1.3.1 sur l’émulateur alors connecté : `adb install -r` a réussi, `dumpsys package` confirme **1.3.1 / code 5**, puis `MainActivity` a été démarrée et le processus est resté actif. Aucune désinstallation ni suppression des données n’a été demandée. Aucun secret utilisateur n’a été lu. Rapport : `livrables/rapports/emulator-update-1.3.1.txt`. Ce contrôle ne valide pas l’authentification réelle Garmin.

### Interface allégée — version 1.6

Les menus de planification, préparation, modèles et séance regroupent les opérations secondaires. Les réglages du profil, options de l’éditeur, données Garmin d’une séance, bilans détaillés, filtres et aperçus de réajustement se déplient à la demande. Les cartes partagées affichent un volume synthétique ; le plan complet reste dans le détail. Les états vides et explications redondants sont réduits. Les erreurs et conflits restent directement visibles.

Trois nouveaux tests Compose vérifient les valeurs du profil après fermeture/réouverture d’une section puis enregistrement, l’heure et les notes d’une séance enregistrée avec leurs sections repliées, et l’accès aux outils de planification et filtres de progression. Les parcours existants sont adaptés aux menus et sélecteurs visibles : création de blocs, navigation entre vues, frise, glisser-déposer, calendrier classique, partage PDF, import Garmin et réajustement.

Captures inspectées à 360 × 800 dp : `profile-essential.png`, `editor-essential.png`, `planning-essential.png`, dans `docs/screenshots`. Les tests restent locaux avec Robolectric ; les tests instrumentés sont compilés. Aucun changement du schéma Room v4 ni du format de sauvegarde, aucune installation sur téléphone réel dans cette livraison. Guide : [INTERFACE.md](INTERFACE.md).

### Réajustement rapide — version 1.5

Le mode Planification propose un décalage de séances par période et sélection, un allègement de semaine et une nouvelle date de compétition avec décalage de la fin de préparation. Les sous-objectifs peuvent être ajustés dans le même aperçu. Les tests vérifient les identifiants stables, notes, séries, dates planifiées, récupérations, allures et répétitions imbriquées conservées, les phases chevauchantes et les objectifs partagés. Les étapes libres et l’arrondi des séries ne produisent pas de volume fictif.

Les collisions sont calculées après le déplacement de tout le lot, y compris avec d’autres préparations. Les destinations passées, sorties de préparation, séances après compétition et liens hors sous-objectif sont signalés. Les séances commencées, terminées, partielles, annulées, importées ou associées à Garmin et les brouillons de résultats restent protégés.

L’aperçu ne réalise aucune écriture. Les tests Room ferment et rouvrent une vraie base après application et vérifient les associations. Une séance ajoutée ou un résultat saisi après l’aperçu bloque l’ensemble de la validation. Un déclencheur SQLite provoque un échec pendant l’écriture des séances : objectif, préparation, phases et séances conservent tous leurs valeurs précédentes.

Les tests Compose ouvrent la fonctionnalité depuis le calendrier, annulent un premier aperçu, appliquent le second, puis retrouvent la séance dans le calendrier classique. Ils vérifient aussi l’allègement, la sélection vide et le bouton de validation désactivé sans changement. Capture du dialogue inspectée : `docs/screenshots/planning-readjustment.png`.

**Portée :** vérification automatique JVM/Robolectric et compilation de l’APK instrumenté ; aucun appareil ADB connecté pour une validation sur téléphone réel. Aucun changement du schéma Room v4 ni du format de sauvegarde JSON : les changements utilisent les données déjà exportées et restaurées.

### Sauvegarde complète et restauration — version 1.4

**18 nouveaux tests réussis.** L’export complet et l’import sur une autre base conservent les exercices, modèles, objectifs, préparations, statuts, séries récurrentes, plans imbriqués, résultats, brouillons non terminés, notes Unicode, profil, apparence, sous-objectifs, liens et associations Garmin. Une double restauration ne crée pas de doublons. Le résultat local corrigé reste conservé lors de la synchronisation Garmin suivant le transfert.

Le format dédié `fr.hybridcoach.backup` version 1 possède un contrôle SHA-256 canonique et une limite de 64 Mio. Les tests vérifient un fichier modifié ou tronqué, les versions incompatibles, les exports de préparation, les références absentes, les identifiants en double, l’imbrication excessive, les clés d’objets réordonnées et les mesures en cours de saisie. Une clé synthétique présente dans le stockage de connexion n’apparaît jamais dans le JSON exporté et ce stockage n’est pas modifié par l’import.

La base passe de **3 à 4** avec une table `athleteSettings` additive. Le profil et le thème de l’ancien DataStore sont copiés une seule fois, sans effacement et sans écraser ensuite un profil Room existant. Un fichier SQLite v3 réel contenant les données du carnet et des associations Garmin est migré puis exporté avec son profil historique. Les anciens tests valident également les chemins **1 → 2 → 3 → 4** et **2 → 3 → 4**.

Le remplacement du carnet et du profil est atomique. Un déclencheur SQLite provoque un échec après le début du remplacement : toutes les données précédentes et le profil restent intacts. Si la copie de sécurité locale ne peut pas être écrite, aucune suppression ne commence. Le carnet précédent est récupérable par export puis restauration de son JSON. Une sauvegarde vide, la fermeture/réouverture de la base et une reprise de synchronisation Garmin sont également couvertes.

Le test Compose ouvre les contrats Android `CreateDocument` et `OpenDocument`, écrit réellement un fichier via un fournisseur de contenu, vérifie l’aperçu sans modification, la case de confirmation obligatoire, la recréation de l’activité, la restauration et la disponibilité du carnet précédent. Un second test vérifie l’annulation du sélecteur et le refus d’un fichier invalide. Capture inspectée : `docs/screenshots/backup-transfer.png`.

Les tests locaux font avancer explicitement la boucle Android Robolectric pour les opérations asynchrones et les retours du sélecteur. Le test de partage PDF réinitialise aussi le cache des racines FileProvider à chaque nouveau contexte de test, comme lors du démarrage réel d’un processus Android. Les anciens parcours de partage et de planification passent dans la suite complète.

**Portée :** le transfert entre deux bases et les parcours locaux sont vérifiés ; le transfert physique entre deux téléphones et les fournisseurs de fichiers tiers restent à vérifier sur les appareils concernés. Aucun appareil ADB n’était connecté à la fin de cette livraison ; aucun carnet personnel n’a été lu ou remplacé. Installer l’APK fourni par-dessus l’ancienne version avant d’exporter. Guide : [SAUVEGARDE.md](SAUVEGARDE.md).

### Historique : correctif de compatibilité et diagnostic HTTP 406

Un HTTP 406 sur « Connecter Garmin » a été confirmé par une capture utilisateur. Le client imposait une réponse JSON au point d’accès de jeton alors que l’exemple Garmin ne fixe pas ce format. Il utilise maintenant `Accept: */*` pour le jeton et peut lire du texte brut, une chaîne JSON ou l’objet OAuth JSON. Le transport s’identifie par le User-Agent HybridCoach. Les erreurs conservent le code HTTP et nomment l’étape qui échoue, sans afficher les corps des réponses. Le 403 est distingué d’une clé refusée en 401 et le 407 est identifié comme une demande d’authentification de proxy.

Six tests supplémentaires couvrent ces formats, un serveur simulé refusant l’ancien en-tête en 406, le diagnostic à chaque étape, la distinction 401/403/407, le rejet d’HTML ou de caractères invalides, ainsi que les propriétés réellement transmises à une connexion HTTP injectée. Le POST sans corps, les certificats HTTPS ordinaires, l’absence de redirections et le non-affichage des erreurs brutes sont conservés.

**Limite :** l’origine exacte du 406 n’est pas confirmée sur le compte réel. Les requêtes de contrôle sans clé valide renvoient 401 ; elles ne reproduisent pas le refus vu par l’utilisateur. La compatibilité est corrigée et testée en simulation ; une nouvelle connexion dans l’application est nécessaire pour valider sa résolution. Aucun secret utilisateur n’a été demandé ni utilisé dans les tests.

### Vérifications de l’API personnelle

Le contrat a été vérifié dans la [FAQ officielle](https://developer.garmin.com/gc-developer-program/personal/personal-faq/) et le [schéma OpenAPI officiel](https://developer.garmin.com/gc-developer-program/personal/swagger-docs/) : hôte HTTPS fixe, génération de jeton par clé personnelle, profil, recherche paginée et détail d’activité. Les appels réseau des tests sont simulés ; aucune clé réelle n’est utilisée.

Les tests vérifient les en-têtes `ApiKey`/`Bearer`, les routes officielles, la réutilisation du jeton en mémoire, son renouvellement à expiration et une seule nouvelle tentative après HTTP 401, les erreurs de clé révoquée, les réponses malformées, les limites de requêtes, 100 activités réparties sur deux pages, les pages dupliquées et les propriétaires incohérents. Ils couvrent les deux formes de réponse documentées, les mètres/secondes/bpm, le passage de minuit entre UTC et heure locale, les mesures absentes et les empreintes stables.

La suppression exige un HTTP 404 de la ressource de détail. Les erreurs 403/429/500 ne deviennent pas des suppressions ; une activité temporairement absente de la liste est récupérée par son détail. Les comptes vides et les changements de profil/propriétaire sont couverts. Une rotation de clé conserve l’identité vérifiée sans confondre deux profils.

Les tests de stockage utilisent AES-GCM avec une clé de test injectée : aller-retour, texte chiffré sans clé en clair, IV différent à chaque écriture, suppression de l’ancienne configuration et déconnexion. Ils ne prétendent pas valider le matériel Keystore d’un téléphone. En production, le fournisseur est Android Keystore.

Les migrations Room réelles **2 → 3** et **1 → 2 → 3** restent couvertes. Le passage à l’API personnelle ne modifiait pas les tables Garmin. Trois tests supplémentaires vérifient la réattribution transactionnelle des comptes issus de l’ancien service après preuve par identifiant d’activité commun, la conservation des corrections locales, l’absence de doublons et le retour arrière si le snapshot est invalide. Aucun ancien compte sans correspondance n’est réattribué arbitrairement.

Le parcours Compose vérifie le formulaire par clé, l’absence de champs serveur/mot de passe et l’effacement du secret après recréation de l’activité. Capture inspectée : `docs/screenshots/garmin-personal.png`. Le parcours d’association confirme une activité ambiguë, ouvre le détail et vérifie sa provenance Garmin. Les tests utilisent exclusivement des activités synthétiques.

**Validation réelle restante :** création d’une clé sur votre compte Garmin, connexion et import de vos activités sur le téléphone. Aucun compte ou clé réelle n’a été fourni. Aucune séance n’a été envoyée vers Garmin ; l’export vers la montre n’est pas implémenté. La synchronisation est manuelle.

Le service Python de la version 1.2 est une archive technique non utilisée par l’application actuelle. Ses 11 tests et ses anciens rapports de conteneur sont conservés à titre historique ; ils n’ont pas été relancés pour cette modification Android.

Les contrôles PDF existants restent validés par un lecteur indépendant. Les rapports actuels sont conservés dans `livrables/rapports/` et le mode d’emploi dans [GARMIN.md](GARMIN.md).

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
| Émulateur Android complet | Mise à jour 1.3.0 → 1.3.1 par `adb install -r` et démarrage réussis sur `emulator-5554` (SDK 37). Tests instrumentés non exécutés ; connexion Garmin réelle à retester |
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
8. Exporter le JSON complet depuis Profil sportif → Sauvegarde et transfert, le transférer sur un second appareil, vérifier l’aperçu puis restaurer et contrôler le carnet et le profil.
9. Ouvrir Profil sportif → Garmin Connect, créer une clé personnelle dans le navigateur, la connecter et synchroniser. Vérifier une deuxième synchronisation sans doublon, les associations proposées et la conservation des séances après déconnexion.

Ce contrôle complémentaire concerne les interactions réelles avec Android et les autres applications ; les résultats sportifs restent locaux et aucun compte n’est nécessaire.
