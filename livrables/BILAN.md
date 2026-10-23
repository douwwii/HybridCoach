# Hybrid Coach 1.6.0 — Interface allégée

Les écrans présentent les séances, les dates et l’action principale. Les opérations secondaires passent dans **Outils**, **Plus** ou **Gérer la préparation**. Les références du profil, options de séance, bilans, filtres, sous-objectifs, notes et données Garmin s’ouvrent à la demande dans des sections dépliantes ou fenêtres.

La planification conserve un sélecteur **Vue** et le bouton **Ajouter une séance** ; le réajustement se trouve dans **Outils**. Son aperçu affiche la synthèse et les alertes ; la sélection individuelle, les changements et les volumes hebdomadaires se déplient séparément. Les cartes de séance présentent un résumé court, avec accès au contenu complet en les ouvrant.

Les valeurs saisies sont conservées lorsqu’on replie une section. Les erreurs et conflits restent visibles. La base Room v4, les plans, performances, associations Garmin et sauvegardes JSON restent compatibles ; aucun effacement ni migration de données n’est nécessaire.

Validation : **145 tests Android/JVM réussis**, APK et tests instrumentés compilés, lint **0 erreur, 27 avertissements et 3 informations**. Trois tests couvrent les formulaires repliés et l’accès aux outils. Les captures de profil, éditeur et planification ont été inspectées sur un écran de 360 × 800 dp. Aucun appareil ADB connecté pour installer ou tester cette version sur téléphone réel.

- APK **1.6.0 / code 8** : `HybridCoach-debug.apk`, signature debug conservée.
- Sources : `HybridCoach-sources.zip`.
- Guide de navigation : `docs/INTERFACE.md`.
- Vérifications : `docs/VERIFICATION.md` et `livrables/rapports/`.
- Réajustement rapide 1.5, sauvegarde/transfert 1.4 et synchronisation Garmin conservés.

Projet modifié dans **Obsidian Vault/My life/Pro/Projets/HybridCoach**. Installer l’APK par-dessus la version précédente ou relancer ce projet depuis Android Studio avec **Run**.
