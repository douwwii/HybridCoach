# Mises à jour depuis GitHub

## Sur le téléphone

Installer une première fois l’APK 1.7 ou ultérieur depuis [les publications officielles](https://github.com/douwwii/HybridCoach/releases/latest). Les versions antérieures ne possèdent pas encore cet écran.

1. Ouvrir **Profil sportif → À propos et mises à jour**.
2. Toucher **Vérifier les mises à jour**. Aucun compte GitHub ni serveur personnel n’est nécessaire.
3. Si une version plus récente existe, consulter sa date, ses nouveautés et sa taille.
4. Toucher **Télécharger la mise à jour**. L’application contrôle la taille, SHA-256, l’identité Android, la version et le certificat de signature de l’APK.
5. Toucher **Installer la mise à jour**. Si nécessaire, autoriser temporairement Hybrid Coach à installer une application depuis les réglages Android ; revenir ensuite à Hybrid Coach. L’installateur Android vérifie la validité de la signature et demande la confirmation finale.

Une installation peut être annulée ; l’application n’annonce pas de succès tant que le nouveau processus n’a pas réellement démarré avec sa nouvelle version. Le fichier ne s’installe jamais silencieusement. Aucune donnée sportive n’est envoyée à GitHub. La recherche est manuelle ; il n’y a pas de tâche périodique ni de notification en arrière-plan.

## Conservation du carnet

Installer **par-dessus** la version précédente, sans désinstaller. La signature, l’identifiant `fr.hybridcoach` et les migrations Room doivent rester compatibles. La vérification refuse une signature différente ou une version plus ancienne. Les notes, résultats, préparations et associations Garmin restent dans la base locale.

Le bouton de sauvegarde complète reste accessible dans les informations de l’écran. Exporter un JSON avant une désinstallation ou un changement de téléphone. Le JSON contient les données personnelles, sans la clé Garmin.

## Erreurs et compatibilité

- **Aucune version stable publiée** : GitHub ne propose pas encore de release stable publique. Les tags seuls, brouillons et préversions ne suffisent pas.
- **Publication incomplète** : le fichier `update.json` ou l’APK attendu manque. Consulter la publication ou réessayer après sa préparation.
- **GitHub limite les requêtes** : réessayer plus tard. L’application ne contient aucun jeton GitHub.
- **Empreinte incorrecte / téléchargement interrompu** : le fichier partiel est supprimé ; recommencer le téléchargement.
- **Signature différente** : ne pas désinstaller sans sauvegarde. Contacter le mainteneur ; une autre clé ne doit pas être utilisée pour une mise à jour.
- **Android trop ancien** : la publication reste consultable, mais le bouton de téléchargement est désactivé pour cette version.

Le fichier APK reste dans le cache privé de l’application, accessible temporairement à l’installateur via FileProvider. Seul le sous-dossier `updates/` est nettoyé lors d’un nouveau téléchargement ; le carnet et les exports restent en place.
