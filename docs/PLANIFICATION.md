# Mode Planification avancée

Accès : **Calendrier → Mode Planification**, ou **Prépas → préparation → Ouvrir la planification avancée**.

## Construire un programme

- Choisir une préparation, ou conserver toutes les séances.
- Basculer entre **Jour**, **Semaine**, **Mois**, **Préparation complète**. Les boutons de vue et les flèches de navigation restent accessibles pendant le défilement.
- Utiliser les flèches, **Aller à la date** ou **Aujourd’hui**. Changer de vue conserve la date sélectionnée.
- Ajouter une séance depuis une journée, une semaine ou le bouton principal. Choisir un modèle ou composer une nouvelle séance.
- Le menu **Actions** permet de dupliquer, déplacer, enregistrer un modèle et supprimer une séance non commencée. **Modifier** ouvre le même éditeur que le calendrier habituel.
- En vue semaine, faire un appui long sur une séance puis la déposer sur un autre jour visible. Le calendrier se parcourt horizontalement. Le menu **Déplacer** permet également de choisir une date éloignée.
- **Dupliquer la semaine** copie les séances de la semaine sélectionnée dans une ou plusieurs semaines consécutives. Si une préparation est sélectionnée, seules ses séances sont copiées. Les copies possèdent leurs propres identifiants, conservent les sous-objectifs et n’héritent d’aucun résultat.

La source de données est la même table Room `workouts`. Une modification apparaît dans toutes les vues, le calendrier habituel et les détails de préparation. Le contenu des séances commencées reste conservé ; leur duplication permet de créer une nouvelle occurrence modifiable.

## Sous-objectifs

Dans une préparation, **Périodes & sous-objectifs → Ajouter** ouvre le formulaire de nom, début, fin et couleur. Toucher une ligne de la frise pour modifier ou supprimer le sous-objectif. Les dates sont inclusives. Les périodes peuvent se chevaucher et dépasser les dates de préparation ; la frise étend alors son axe pour les inclure.

Associer plusieurs sous-objectifs dans l’éditeur d’une séance, ou via **Actions → Sous-objectifs**. Seuls ceux de la préparation choisie sont proposés. Une association reste en place lorsque les dates du sous-objectif ou de la séance changent ; elle n’est pas limitée automatiquement à l’intersection des périodes. Retirer/changer la préparation supprime les associations devenues incompatibles. Supprimer un sous-objectif retire ses liens sans supprimer les séances.

La frise présente une ligne par sous-objectif, avec un axe temporel commun. La vue complète indique les sous-objectifs actifs chaque semaine, le nombre de séances non annulées, le kilométrage de course connu et le temps calculable des étapes structurées. Les volumes manquants ne sont pas estimés.

## Répétitions structurées

L’éditeur propose **Composer un fractionné** pour saisir en un seul formulaire : répétitions, effort en mètres ou secondes, cible d’intensité, récupération en mètres ou secondes, récupération active/passive, nombre de blocs, récupération entre blocs, échauffement et retour au calme. Une durée nulle omet la récupération ; un échauffement ou retour au calme vide/nul est omis.

Exemple : 4 répétitions, effort 1 000 m, allure 210 s/km, récupération 120 s produit un bloc `REPEAT` avec un enfant `WORK` et une récupération structurée. Il contient quatre efforts et trois récupérations.

Exemple imbriqué : 3 blocs de 4 × 400 m à 195 s/km, récupération 60 s et récupération entre blocs 180 s, avec 1 200 s d’échauffement et 600 s de retour au calme. L’arbre contient un bloc externe de trois répétitions, un bloc interne de quatre efforts et des récupérations distinctes à chaque niveau. Le développement produit 12 efforts, 9 récupérations internes et 2 récupérations entre blocs.

Chaque étape ou bloc peut être modifié, dupliqué avec tous ses enfants, supprimé et réordonné dans son niveau avec les flèches. Un bloc peut contenir d’autres blocs. Les étapes structurées sont disponibles en course, compétition, autre sport et en complément d’une séance de musculation. Les cibles avancées (plages d’allure, zones, références, VMA) restent accessibles via **Modifier** sur l’étape d’effort.

Les récupérations explicites d’un bloc sont exécutées **uniquement entre les répétitions**, jamais après la dernière. Les anciennes récupérations placées comme enfants restent exécutées telles qu’elles avaient été enregistrées, y compris à la fin de chaque passage. Les nouveaux champs ont des valeurs par défaut pour lire les séances existantes.

Les arbres sont conservés dans le JSON du plan, affichés dans les cartes du calendrier et dans le détail de séance, et exportés sans développement en une liste textuelle. Le PDF restitue la hiérarchie ; le JSON v2 conserve les identifiants, cibles, enfants et récupérations. Cela prépare un futur adaptateur vers les montres ; aucun transfert vers une montre n’est fourni dans cette version.

## Migration

Room passe de **1 à 2** avec `MIGRATION_1_2`, enregistrée dans `CoachApplication`. Deux tables s’ajoutent : `subGoals` et `workoutSubGoals`. Les tables existantes ne sont pas recréées. Les clés étrangères assurent le nettoyage des associations lors des suppressions. Le dépôt vérifie transactionnellement qu’une séance et ses sous-objectifs appartiennent à la même préparation.

Les schémas 1 et 2 sont conservés dans `app/schemas`. Un test crée un véritable fichier SQLite à partir du schéma 1, y inscrit objectifs, préparation, séance terminée, résultat, modèle et exercice, puis l’ouvre via Room 2 et vérifie la conservation de ces données.
