# Hybrid Coach 1.3.1 — compatibilité de connexion Garmin

Le HTTP 406 à la connexion est confirmé par une capture utilisateur. La demande de jeton n’impose plus JSON et accepte aussi une réponse texte ; les erreurs indiquent désormais le code HTTP et l’étape exacte. Ce correctif de compatibilité reste à confirmer par une nouvelle tentative sur le compte réel, dont aucune clé n’a été communiquée.

Le projet Obsidian Vault utilise désormais directement l’API personnelle officielle Garmin. Dans Profil sportif → Garmin Connect, créer une clé avec le lien officiel, la coller et toucher Connecter Garmin, puis Synchroniser Garmin. Aucun serveur, domaine, Docker ni mot de passe Garmin n’est nécessaire dans l’application. Le jeton est renouvelé automatiquement depuis la clé ; la synchronisation des activités reste manuelle.

Les activités retrouvent le calendrier, les séances prévues et les statistiques existantes. Les doublons, corrections, cas ambigus et suppressions confirmées sont gérés. La base reste en version 3 et les anciennes associations du service sont conservées après vérification par identifiant Garmin commun. La clé est chiffrée sur Android ; le jeton reste en mémoire. La déconnexion conserve l’historique local.

Validation : **107 tests Android/JVM réussis**, APK et tests instrumentés compilés, lint avec **0 erreur, 26 avertissements et 3 informations**. Signature APK vérifiée et identique à la précédente livraison. Les échanges Garmin sont simulés dans les tests ; la connexion à votre compte réel reste à valider après saisie de votre clé sur le téléphone. L’envoi des programmes vers la montre n’est pas implémenté.

- APK 1.3.1 / code 5 : `HybridCoach-debug.apk`.
- Sources actualisées : `HybridCoach-sources.zip`.
- Mode d’emploi actuel : `docs/GARMIN.md`.
- Vérifications et limites : `docs/VERIFICATION.md` et `livrables/rapports/`.
- L’ancienne archive `HybridCoach-garmin-bridge.zip` concerne uniquement la version 1.2 ; elle n’est plus nécessaire. Le code du service et son guide sont conservés pour maintenance.

Installer la nouvelle version par-dessus l’ancienne signée avec la même clé. Ne pas désinstaller si vous souhaitez conserver votre base locale.

La version 1.3.1 a aussi été installée par-dessus la 1.3.0 sur l’émulateur connecté (SDK 37) et démarrée avec succès, sans désinstallation ni effacement demandé. L’utilisateur doit réessayer la connexion avec sa clé ; le secret n’a pas été lu par l’agent.
