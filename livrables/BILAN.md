# Livraison Hybrid Coach 1.0.0

Vérification : 16/10/2026 à 11:04 (Europe/Paris).

- APK debug : `HybridCoach-debug.apk` (27.6 Mio), signature v2 vérifiée.
- Sources Android Studio : `HybridCoach-sources.zip`, sans SDK ni caches locaux.
- 45 tests JVM / Android local réussis, zéro échec.
- Compilation de l’application et de l’APK de tests instrumentés réussie.
- Android Lint : zéro erreur, 20 avertissements, 3 informations ; rapports joints.
- PDF relus avec un moteur indépendant, rendus avec Poppler, marges contrôlées sur 26 pages au total.
- Les essais sur téléphone physique et sous Android Studio Windows restent à faire. L’émulateur de cet environnement a planté avant connexion ADB.

Le projet comprend le calendrier, les récurrences, les modèles, la bibliothèque d’exercices, l’exécution de force, les séances de course structurées, le profil, les préparations, l’historique, la progression et les exports PDF/JSON. Voir le README pour les détails fonctionnels et les limites.

Les résultats de l’utilisateur ne sont jamais préremplis avec les données des tests. La base locale est créée automatiquement au premier lancement. Les PDF et captures de `docs/screenshots` illustrent uniquement les essais automatisés.

SHA-256 de l’APK : `ff3bda435c31cdbc2a8c3b76485508e8f3ebb7e85e1f693e9f0e016590d0f4cd`.
