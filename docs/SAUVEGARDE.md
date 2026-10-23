# Sauvegarde JSON et changement de téléphone — Hybrid Coach 1.4

## Transférer son carnet

Sur l’ancien téléphone :

1. Installer Hybrid Coach **1.4.0 ou une version compatible plus récente**, par-dessus l’application existante, sans la désinstaller.
2. Ouvrir **Profil sportif → Sauvegarde et transfert**.
3. Choisir **Exporter ma sauvegarde JSON** et enregistrer le fichier avec le sélecteur Android, dans un dossier ou un fournisseur de documents de votre choix.
4. Attendre le message **Sauvegarde JSON complète enregistrée**, puis transférer le fichier vers le nouveau téléphone, par exemple par USB ou via votre stockage de fichiers habituel.

Sur le nouveau téléphone :

1. Installer Hybrid Coach 1.4.0 ou une version compatible plus récente.
2. Ouvrir **Profil sportif → Sauvegarde et transfert → Restaurer un fichier JSON**.
3. Choisir le fichier. L’application contrôle son format et son intégrité avant de proposer un aperçu daté des séances, résultats, préparations, modèles, exercices personnels, sous-objectifs et activités Garmin.
4. Vérifier le contenu, cocher **Je souhaite remplacer le carnet de ce téléphone**, puis toucher **Remplacer et restaurer**.
5. Attendre le message de réussite. Le profil, le thème et les données du calendrier sont immédiatement disponibles ; aucune connexion Internet n’est nécessaire pour consulter le carnet restauré.
6. Reconnecter Garmin sur le nouveau téléphone si vous utilisez cette intégration.

L’import **remplace entièrement** le carnet et le profil du téléphone cible ; il ne fusionne pas deux carnets. La sélection d’un fichier, son aperçu, l’annulation ou une erreur de validation ne remplacent aucune donnée sportive existante.

## Contenu du fichier

La sauvegarde contient toutes les données locales utilisées par le carnet :

- Exercices de référence et exercices personnels, modèles et plans structurés.
- Objectifs, préparations actives ou archivées, périodes et liens séance/sous-objectif.
- Séances, dates réelles et prévues, horaires, statuts, identifiants et séries récurrentes.
- Résultats, séries cochées ou en cours de saisie, intervalles, annotations, ressentis et chronos enregistrés.
- Blocs de répétitions, récupérations et répétitions imbriquées, avec leurs identifiants d’origine.
- Profil sportif, allures, zones et apparence.
- Activités Garmin locales, associations confirmées ou automatiques, ignorés, suppressions connues, dernières révisions et instantanés antérieurs permettant la dissociation.

Les identifiants sont conservés. Restaurer deux fois le même fichier remplace le même carnet et ne crée pas de doublons. Après reconnexion au même compte Garmin, les identifiants distants et empreintes permettent de reprendre la synchronisation sans dupliquer les séances ni écraser une correction locale lorsque l’activité distante est inchangée.

**Ne sont pas transférés :** clé personnelle Garmin, jetons de connexion, clé Android Keystore, autorisations de fichiers, enregistrements système d’alarmes, caches et fichiers PDF/JSON précédemment partagés. Les données permettant de régénérer les exports sont incluses. Les connexions déjà présentes sur le téléphone cible ne sont pas modifiées par la restauration.

Les séances en cours et leurs horodatages sont conservés. Après restauration, les anciennes alarmes locales de repos sont annulées et celles des séances actives sont reprogrammées si leur échéance est encore future. Le chrono tient compte du temps écoulé depuis son démarrage original.

## Copie de sécurité avant restauration

Avant de remplacer le carnet, l’application écrit une copie complète du carnet et du profil actuels dans son stockage privé durable. Si cette écriture échoue, la restauration est annulée avant tout remplacement. Une seule copie du **carnet avant la dernière restauration** est conservée.

Pour revenir en arrière : choisir **Exporter le carnet précédent**, enregistrer le JSON, puis le sélectionner avec **Restaurer un fichier JSON** et confirmer. Le fichier est contrôlé comme une sauvegarde ordinaire.

Cette copie locale reste sur le téléphone concerné ; elle ne remplace pas une sauvegarde externe et disparaît lors d’une désinstallation ou d’un effacement des données de l’application. Le JSON enregistré via le sélecteur Android est indépendant de ce stockage privé.

## Confidentialité et intégrité

Le fichier JSON est **en clair** et contient notamment les notes et performances personnelles. Il doit être conservé dans un emplacement approprié. L’application ne l’envoie à aucun destinataire : vous choisissez vous-même l’emplacement et le moyen de transfert.

Le marqueur `fr.hybridcoach.backup`, la version de format, la version de base et une empreinte **SHA-256** contrôlent le fichier. Cette empreinte détecte les modifications ou troncatures accidentelles ; elle n’est ni un chiffrement ni une signature garantissant l’identité de l’auteur. Un fichier réordonné ou reformaté sans modifier ses valeurs conserve son intégrité. Ne pas éditer manuellement son contenu.

Les fichiers dépassant **64 Mio** ou présentant une imbrication excessive sont refusés. Les doublons d’identifiants, références absentes, liens incohérents, plans invalides et formats plus récents non pris en charge sont également refusés. Les exports JSON de préparation ne sont pas des sauvegardes complètes et sont signalés comme incompatibles.

## Format versionné

Le format de sauvegarde est indépendant de l’export de préparation (`fr.hybridcoach.preparation`, version 2).

```text
format: "fr.hybridcoach.backup"
version: 1
appVersion: version de Hybrid Coach ayant créé le fichier
exportedAt: instant UTC ISO 8601
databaseVersion: 4 à partir de la version 1.4
data:
  profile, theme
  exercises, templates, goals, preparations
  workouts, results, subGoals, workoutSubGoals
  garminActivities, garminSync
sha256: empreinte du document sans le champ sha256
```

L’empreinte est calculée sur les objets JSON dont les clés sont triées récursivement, sans espaces de présentation ; l’ordre des listes est conservé. Les plans, résultats et instantanés historiques restent dans leur représentation JSON d’origine dans les colonnes sérialisées, sans réécriture des identifiants.

Une évolution future doit maintenir un décodeur compatible ou introduire une nouvelle version de format avec une migration explicite. Toute nouvelle donnée persistante doit être intégrée à la sauvegarde et à sa restauration, puis testée.

## Stockage et mise à jour depuis les versions précédentes

La base Room passe de **3 à 4** avec `MIGRATION_3_4`, qui ajoute uniquement `athleteSettings`. Aucun effacement destructif n’est activé. Les migrations depuis les versions 1 et 2 restent enregistrées.

Le profil et le thème auparavant conservés dans DataStore sont copiés une seule fois vers Room, sans supprimer le fichier historique. Un profil déjà présent dans Room n’est jamais remplacé par cette ancienne copie. Cela permet de restaurer le carnet, le profil et le thème dans **une seule transaction** : en cas d’échec SQL, les données précédentes restent intactes.

L’export lit toutes les tables dans une transaction cohérente. Les commandes locales et l’import final Garmin sont coordonnés avec les sauvegardes et restaurations. Un téléchargement Garmin ne bloque pas la saisie locale ; s’il a commencé avant une restauration du carnet, son import est refusé et une nouvelle synchronisation est demandée.

## Vérifications

Les tests utilisent uniquement des données synthétiques. Ils couvrent le transfert complet entre deux bases, le profil et les notes Unicode, les répétitions imbriquées, les séries en cours de saisie, les associations Garmin, le retour au carnet précédent, une panne SQL après le début du remplacement, l’impossibilité d’écrire la copie de sécurité, une sauvegarde vide, l’idempotence, la reprise Garmin et la réouverture de la base.

Le parcours Compose teste les contrats Android de création et d’ouverture de documents, le fichier réellement écrit via un fournisseur de contenu, l’aperçu sans modification, la confirmation obligatoire, la recréation d’activité, l’annulation et un fichier invalide. Les tests JVM locaux font avancer explicitement la boucle principale Robolectric pour les retours du sélecteur et les opérations asynchrones.

Les résultats exacts, l’APK et les limites de validation sont indiqués dans [VERIFICATION.md](VERIFICATION.md). Le transfert physique entre deux téléphones et les fournisseurs de documents tiers restent à vérifier sur les équipements concernés.
