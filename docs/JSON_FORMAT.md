# Export de préparation, version 2

Pour transférer l’intégralité de l’application sur un autre téléphone, utiliser le format distinct décrit dans [SAUVEGARDE.md](SAUVEGARDE.md). Le présent format est un export partiel de préparation.

Le type Kotlin de référence est `PreparationDocument` dans `export/PreparationExport.kt`.

```json
{
  "format": "fr.hybridcoach.preparation",
  "version": 2,
  "exportedAt": "2026-10-16T08:00:00Z",
  "options": { "personalNotes": false, "performances": true },
  "goal": {},
  "preparation": {},
  "subGoals": [{ "id": "phase-id", "preparationId": "prep-id", "name": "Seuil", "start": "2026-10-12", "end": "2026-11-01", "color": 1 }],
  "weeks": [
    {
      "start": "2026-10-12",
      "end": "2026-10-18",
      "plannedKm": 0.0,
      "actualKm": 0.0,
      "sessions": [
        { "session": {}, "planned": { "strength": [], "running": [] }, "result": null, "subGoalIds": ["phase-id"] }
      ]
    }
  ]
}
```

Extrait structurel : les objets vides ci-dessus représentent les objets complets définis dans le code, pas un fichier importable. Les exports réels contiennent tous leurs champs.

Les UUID permettent de relier les séances aux préparations, les résultats aux séances et les séries aux instances d’exercice. Un exercice possède un identifiant de bibliothèque et un identifiant d’instance dans le plan. Les intervalles identifient l’étape source et son numéro après développement des répétitions. Le plan décodé est fourni dans `planned`, et sa sérialisation est également conservée dans `session.planJson`.

Les dates sont `YYYY-MM-DD`. Les allures sont en secondes/km, les distances d’étapes en mètres, les résultats de course en kilomètres, les durées en secondes, les charges en kilogrammes. Les valeurs de résultat saisies sont des chaînes pour conserver la saisie progressive et les champs absents. Les enums utilisent leurs identifiants Kotlin (`RUN`, `STRENGTH`, `DONE`, etc.).

`result` est null si aucun résultat n’existe ou si l’export des performances est désactivé. `actualKm` est null lorsque les performances sont exclues. Une chaîne vide signifie « non renseigné », pas une performance nulle. Le champ `options` décrit les exclusions appliquées. L’import de ces fichiers n’est pas encore fourni dans l’interface.

## Blocs de répétitions

Le champ historique `planned.running` contient les étapes structurées de tous les sports. Un bloc possède `kind: "REPEAT"`, `repetitions`, une liste `children`, et une étape `recovery` facultative. Celle-ci est de type `RECOVERY`, ne contient aucun enfant et est exécutée entre deux passages uniquement. Les durées/distances de récupération utilisent les mêmes unités et types que l’effort. `activeRecovery` distingue la récupération active de la passive. Les blocs peuvent être imbriqués ; chaque niveau conserve sa propre récupération. La liste n’est pas développée dans l’export, afin de conserver la structure pour un futur adaptateur de montre.

Les anciens plans sans `recovery` sont lus avec `null`. Les documents v1 restent décodables : `subGoals` et `subGoalIds` prennent une liste vide par défaut. Les notes de récupération sont retirées récursivement lorsque `personalNotes` est faux. Les sous-objectifs sont exportés avec leurs dates même lorsqu’ils n’ont aucune séance associée.

## Provenance Garmin

Les séances exportées portent `source` (`MANUAL` ou `GARMIN`), `date` (jour réellement affiché), `plannedDate` (jour initial d’une séance planifiée associée) et `plannedTime` facultatif. Les mesures réelles restent dans `result` ; le programme initial reste dans `planned`. Les agrégats hebdomadaires prévus utilisent la date initiale ; les agrégats réalisés utilisent la date effective. Les activités à confirmer et celles ignorées ne créent pas d’occurrence supplémentaire exportée. Aucun jeton ou identifiant d’authentification du service n’entre dans les exports de préparation.
