package fr.hybridcoach.data

fun exerciseLibrary(): List<Exercise> {
    val groups =
        listOf(
            Triple(
                "Pectoraux",
                "Poussée",
                listOf(
                    "Développé couché|Barre",
                    "Développé couché haltères|Haltères",
                    "Développé incliné|Barre",
                    "Développé incliné haltères|Haltères",
                    "Développé décliné|Barre",
                    "Écarté couché|Haltères",
                    "Écarté à la poulie|Poulie",
                    "Pec deck|Machine",
                    "Pompes|Poids du corps",
                    "Dips pectoraux|Barres parallèles",
                ),
            ),
            Triple(
                "Dos",
                "Tirage",
                listOf(
                    "Tractions pronation|Barre fixe",
                    "Tractions supination|Barre fixe",
                    "Tirage vertical|Poulie",
                    "Tirage horizontal|Poulie",
                    "Rowing barre|Barre",
                    "Rowing haltère unilatéral|Haltère",
                    "Rowing poitrine appuyée|Haltères",
                    "Tirage bras tendus|Poulie",
                    "Pull-over|Haltère",
                    "Face pull|Poulie",
                    "Rowing T-bar|Machine",
                ),
            ),
            Triple(
                "Quadriceps",
                "Flexion de genou",
                listOf(
                    "Squat|Barre",
                    "Front squat|Barre",
                    "Goblet squat|Haltère",
                    "Presse à cuisses|Machine",
                    "Hack squat|Machine",
                    "Fentes marchées|Haltères",
                    "Fentes arrière|Haltères",
                    "Squat bulgare|Haltères",
                    "Leg extension|Machine",
                    "Step-up|Haltères",
                ),
            ),
            Triple(
                "Ischio-jambiers",
                "Charnière de hanche",
                listOf(
                    "Soulevé de terre|Barre",
                    "Romanian deadlift|Barre",
                    "Soulevé de terre jambes tendues|Haltères",
                    "Leg curl allongé|Machine",
                    "Leg curl assis|Machine",
                    "Nordic curl|Poids du corps",
                    "Good morning|Barre",
                ),
            ),
            Triple(
                "Fessiers",
                "Extension de hanche",
                listOf(
                    "Hip thrust|Barre",
                    "Pont fessier|Poids du corps",
                    "Hip thrust unilatéral|Poids du corps",
                    "Kickback|Poulie",
                    "Abduction de hanche|Machine",
                    "Soulevé de terre sumo|Barre",
                ),
            ),
            Triple(
                "Épaules",
                "Poussée / élévation",
                listOf(
                    "Développé militaire|Barre",
                    "Développé épaules haltères|Haltères",
                    "Développé Arnold|Haltères",
                    "Élévations latérales|Haltères",
                    "Élévations latérales poulie|Poulie",
                    "Oiseau|Haltères",
                    "Reverse pec deck|Machine",
                    "Élévations frontales|Haltères",
                    "Landmine press|Barre",
                ),
            ),
            Triple(
                "Biceps",
                "Flexion de coude",
                listOf(
                    "Curl biceps|Barre",
                    "Curl haltères|Haltères",
                    "Curl incliné|Haltères",
                    "Curl marteau|Haltères",
                    "Curl pupitre|Barre EZ",
                    "Curl à la poulie|Poulie",
                ),
            ),
            Triple(
                "Triceps",
                "Extension de coude",
                listOf(
                    "Extensions triceps|Poulie",
                    "Extension triceps au-dessus de la tête|Haltère",
                    "Barre au front|Barre EZ",
                    "Développé couché prise serrée|Barre",
                    "Dips triceps|Barres parallèles",
                ),
            ),
            Triple(
                "Mollets",
                "Flexion plantaire",
                listOf(
                    "Extensions mollets debout|Machine",
                    "Extensions mollets assis|Machine",
                    "Mollets unilatéraux|Haltère",
                    "Mollets à la presse|Machine",
                ),
            ),
            Triple(
                "Abdominaux",
                "Stabilisation / flexion",
                listOf(
                    "Crunch|Poids du corps",
                    "Crunch à la poulie|Poulie",
                    "Relevé de jambes|Barre fixe",
                    "Gainage|Poids du corps",
                    "Gainage latéral|Poids du corps",
                    "Ab wheel|Roue abdominale",
                    "Dead bug|Poids du corps",
                    "Pallof press|Poulie",
                    "Russian twist|Haltère",
                ),
            ),
        )
    return groups.flatMap { (muscle, movement, names) ->
        names.map { entry ->
            val (name, equipment) = entry.split('|')
            val secondary =
                when (muscle) {
                    "Pectoraux" -> "Triceps, épaules"
                    "Dos" -> "Biceps, avant-bras"
                    "Quadriceps" -> "Fessiers, abdominaux"
                    "Ischio-jambiers" -> "Fessiers, dos"
                    "Épaules" -> "Triceps"
                    else -> ""
                }
            Exercise(
                id = "builtin-" + java.util.UUID.nameUUIDFromBytes(name.toByteArray()),
                name = name,
                muscle = muscle,
                secondary = secondary,
                equipment = equipment,
                movement = movement,
            )
        }
    }
}
