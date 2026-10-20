# Architecture de Hybrid Coach

Application Android native, Kotlin, Compose Material 3, MVVM et Navigation Compose. L’injection est explicite via `CoachApplication` ; fonctions locales sans compte ; import Garmin facultatif via un service personnel HTTPS.

## Données

Room constitue la source de vérité. Les identifiants UUID restent stables. Les dates sportives sont des dates civiles ISO (`LocalDate`), sans conversion de fuseau ; les débuts, fins et échéances de repos sont des instants en millisecondes.

- `exercises` : bibliothèque de référence et exercices personnels ; les noms, muscles et paramètres sont copiés dans chaque plan.
- `templates` : modèles réutilisables contenant un plan sérialisé.
- `goals` et `preparations` : objectifs et périodes, avec clé étrangère restrictive.
- `workouts` : occurrences planifiées, avec association facultative à une préparation et identifiant de série récurrente.
- `subGoals` : périodes personnalisées d’une préparation, pouvant se chevaucher.
- `workoutSubGoals` : liaison plusieurs-à-plusieurs, indexée avec suppression en cascade. Le dépôt impose la cohérence avec la préparation de la séance.
- `results` : résultat propre à une occurrence, avec séries, intervalles, ressenti et horodatages. Suppression en cascade seulement après suppression explicite de la séance.

Les plans de musculation et les arbres d’étapes sont des agrégats JSON versionnés par le schéma applicatif, dans des colonnes Room. Ce choix réduit les jointures et rend les instantanés indépendants. Les relations entre agrégats restent des clés étrangères SQL. Il n’y a pas de table séparée par série ou étape ; les statistiques parcourent les agrégats locaux.

Les occurrences créées depuis un modèle copient son contenu. Modifier un modèle ne modifie aucune occurrence. Les allures calculées depuis le profil sont résolues lors de l’enregistrement d’une séance ; les résultats historiques restent indépendants des changements de profil. Les séances commencées ou terminées ne permettent plus de réécrire le plan : il faut les dupliquer. Leurs résultats peuvent être corrigés.

## Écritures et reprise

`CoachViewModel` met les commandes dans une file FIFO. Les mises à jour de résultats lisent puis modifient le dernier résultat à l’intérieur d’une transaction Room. Cela évite qu’une modification de charge écrase une modification de répétitions. Chaque changement de champ est envoyé à la base ; une barre indique les écritures en cours, et les erreurs restent visibles.

La séance en cours, les séries validées et les échéances de repos survivent à la fermeture du processus. Le chrono est calculé à partir des horodatages conservés et inclut les interruptions. Les brouillons des éditeurs survivent aux recréations d’activité via `rememberSaveable` ; ils ne sont pas des séances persistées avant validation.

DataStore conserve le profil sportif et le thème. Les zones sont définies par l’utilisateur, sans formule physiologique imposée. Les saisies numériques acceptent la virgule décimale.

## Répétitions et récurrences

L’arbre d’étapes, disponible dans tous les sports sauf le repos, supporte cinq niveaux de répétition, avec une limite de 10 000 étapes développées par séance. Cette borne empêche les expansions accidentelles démesurées. Les flèches réordonnent les éléments à l’intérieur d’un même niveau ; pour changer de bloc, recréer l’étape dans le bloc voulu.

Les récurrences hebdomadaires sont matérialisées entre deux dates inclusives. Une opération couvre au maximum dix ans, sans horizon global du calendrier. Modifier les occurrences futures n’affecte que les statuts prévue/reportée et applique le décalage de date à partir de l’occurrence sélectionnée. Une copie de semaine conserve le plan et l’association à la préparation, sans performances ni notes de résultat. Les doublons de planification volontaires sont autorisés.

## Calculs

- Allure en secondes par kilomètre = 3600 / vitesse en km/h.
- Temps cible = distance en mètres / 1000 × allure.
- % VMA : vitesse cible = VMA × pourcentage / 100.
- Les plages et zones d’allure utilisent leur milieu pour estimer la distance ou la durée prévue. Les bornes d’origine sont conservées et affichées.
- Les étapes sans distance calculable ne contribuent pas au kilométrage prévu. Les données réelles manquantes ne sont pas estimées.
- Seules les séances terminées ou partielles contribuent aux statistiques réelles.
- Le volume de force est la somme des charges saisies × répétitions des séries cochées. Le poids du corps n’est pas ajouté.
- Epley : charge × (1 + répétitions / 30), pour 2 à 12 répétitions ; une répétition utilise directement la charge. Estimation réservée aux modes standard et unilatéral, hors poids du corps, lesté et assisté.
- Une série est attribuée uniquement au muscle principal. La fréquence est le nombre de séances contenant des séries cochées de ce muscle / nombre de semaines affichées.

## Exports

Le document intermédiaire `PreparationDocument` est partagé par les sorties JSON et PDF. Le JSON porte un identifiant de format et une version `2`. Les notes privées sont retirées récursivement des plans, objectifs et résultats si elles sont décochées. La description publique de séance reste incluse. Les séances associées hors période sont également exportées.

Le PDF est généré par PDFBox-Android, avec pagination, police Liberation Sans intégrée et découpage du texte selon les métriques de la police. Les caractères sans glyphe sont remplacés par `?` dans le PDF ; le JSON conserve l’Unicode original. Les fichiers restent dans le cache privé ; le partage utilise `FileProvider`, une URI `content://` et une autorisation de lecture temporaire. Aucun accès général au stockage n’est demandé.

## Évolutions et migrations

La base est à la version **3**. `MIGRATION_2_3` ajoute la provenance et l’historique de date des séances ainsi que les tables Garmin, sans altérer les valeurs existantes. Les liens sur un identifiant Garmin et sur une séance sont uniques. `MIGRATION_1_2` ajoute les tables des sous-objectifs et des associations sans altérer les tables existantes. Elle est enregistrée dans `CoachApplication` et vérifiée avec un fichier SQLite réellement créé selon le schéma 1. Les trois schémas sont conservés dans `app/schemas`. Aucun effacement destructif automatique n’est configuré.

Les nouveaux champs sérialisés possèdent des valeurs par défaut. `RunningStep.recovery`, facultatif, représente une récupération entre les répétitions uniquement ; les anciens enfants de type récupération conservent leur sémantique. Les calculs, le développement des intervalles, l’affichage et les exports parcourent cette structure. Une copie renouvelle tous les identifiants d’instance, y compris ceux des enfants et récupérations.

`PlanningScreen` et le calendrier classique observent le même `CoachState` issu de Room. Les opérations de déplacement, copie et association passent par les transactions du dépôt. Les sous-objectifs sont indépendants des dates de séance : déplacer une séance ne modifie pas automatiquement ses liens. Les dates de début/fin d’un sous-objectif sont inclusives ; elles peuvent dépasser la préparation et la frise étend son axe en conséquence. Voir [le guide de planification](PLANIFICATION.md).

Un futur import Strava peut passer par un adaptateur transformant l’activité distante en `WorkoutResult` et `IntervalLog`, puis utiliser les transactions du dépôt. Aucun SDK de fournisseur n’est couplé à l’interface actuelle.


## Garmin Connect

Le paquet `garmin/` sépare le contrat réseau `GarminGateway`, le client HTTPS, la configuration chiffrée Android Keystore, le rapprochement pur et les transactions d’import. `GarminImporter` conserve les instantanés antérieurs d’une séance associée afin de restaurer ses mesures et son statut lors d’une dissociation/suppression. L’occurrence planifiée garde son UUID ; elle apparaît au jour réel, et `plannedDate` garde le jour initial pour le volume prévu. Les activités ambiguës restent en attente et hors statistiques jusqu’à décision.

Le service `services/garmin_bridge` exécute le SDK Python dans un environnement Linux fourni. Les jetons et son cache sont chiffrés, ses endpoints sont authentifiés et le proxy Caddy assure le transport HTTPS. Aucun jeton Garmin n’est transmis à Android. Les suppressions ne se déduisent pas d’une page manquante : elles sont confirmées par la ressource distante. `GarminWorkoutExporter` réserve l’interface de l’étape 2 sans implémentation d’envoi. Voir [le guide détaillé](GARMIN.md).
