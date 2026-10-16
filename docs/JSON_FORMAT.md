# Export de préparation, version 1

Le type Kotlin de référence est `PreparationDocument` dans `export/PreparationExport.kt`.

```json
{
  "format": "fr.hybridcoach.preparation",
  "version": 1,
  "exportedAt": "2026-10-16T08:00:00Z",
  "options": { "personalNotes": false, "performances": true },
  "goal": {},
  "preparation": {},
  "weeks": [
    {
      "start": "2026-10-12",
      "end": "2026-10-18",
      "plannedKm": 0.0,
      "actualKm": 0.0,
      "sessions": [
        { "session": {}, "planned": { "strength": [], "running": [] }, "result": null }
      ]
    }
  ]
}
```

Extrait structurel : les objets vides ci-dessus représentent les objets complets définis dans le code, pas un fichier importable. Les exports réels contiennent tous leurs champs.

Les UUID permettent de relier les séances aux préparations, les résultats aux séances et les séries aux instances d’exercice. Un exercice possède un identifiant de bibliothèque et un identifiant d’instance dans le plan. Les intervalles identifient l’étape source et son numéro après développement des répétitions. Le plan décodé est fourni dans `planned`, et sa sérialisation est également conservée dans `session.planJson`.

Les dates sont `YYYY-MM-DD`. Les allures sont en secondes/km, les distances d’étapes en mètres, les résultats de course en kilomètres, les durées en secondes, les charges en kilogrammes. Les valeurs de résultat saisies sont des chaînes pour conserver la saisie progressive et les champs absents. Les enums utilisent leurs identifiants Kotlin (`RUN`, `STRENGTH`, `DONE`, etc.).

`result` est null si aucun résultat n’existe ou si l’export des performances est désactivé. `actualKm` est null lorsque les performances sont exclues. Une chaîne vide signifie « non renseigné », pas une performance nulle. Le champ `options` décrit les exclusions appliquées. L’import de ces fichiers n’est pas encore fourni dans l’interface.
