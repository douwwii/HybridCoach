# Garmin Connect — connexion directe par API personnelle

Depuis **Hybrid Coach 1.3.0**, aucun serveur, Docker, domaine ni clé de service intermédiaire n’est nécessaire. L’application Android communique en HTTPS avec l’API personnelle officielle de Garmin. Cette connexion est facultative ; les fonctions locales restent utilisables hors ligne.

## Connecter son compte

1. Installer l’APK 1.3.1 ou lancer le projet de ce dossier dans Android Studio.
2. Ouvrir **Profil sportif → Garmin Connect**.
3. Toucher **Créer une clé dans Garmin Connect**. Le navigateur ouvre [la page officielle des clés personnelles](https://connect.garmin.com/app/settings/personalApiToken). Se connecter à Garmin dans ce navigateur si nécessaire.
4. Choisir **Create Key**, accepter les conditions de Garmin et choisir la durée de validité. Copier la clé affichée une seule fois.
5. Revenir dans Hybrid Coach, coller la clé dans **Clé personnelle Garmin**, puis toucher **Connecter Garmin**. La connexion est vérifiée avant l’enregistrement de la clé.
6. Toucher **Synchroniser Garmin**. Le calendrier, l’historique et les statistiques utilisent immédiatement les activités importées.
7. Examiner **Activités à examiner** si une association à une séance prévue demande confirmation. Choisir une séance proposée ou **Activité non planifiée**.

Ne pas saisir la clé de l’ancien service Python : il faut une clé créée par Garmin. Le mot de passe et le code MFA restent dans le parcours Garmin du navigateur. Ils ne sont plus demandés par Hybrid Coach.

La synchronisation est **manuelle**. La montre doit d’abord avoir synchronisé son activité avec Garmin Connect. L’application télécharge ensuite l’historique paginé disponible dans l’API, sans filtre de date. Elle ne modifie ni ne supprime rien dans le compte Garmin. L’envoi des programmes et des séances structurées vers la montre n’est pas implémenté.

## Correctif 1.3.1 — erreur HTTP 406 à la connexion

Une capture utilisateur a confirmé un HTTP 406 après « Connecter Garmin » en version 1.3.0. La requête de jeton imposait `Accept: application/json`, alors que l’exemple officiel Garmin ne précise pas ce format de réponse. La version 1.3.1 utilise `Accept: */*` pour ce point d’accès et accepte un jeton en texte brut, une chaîne JSON ou l’objet OAuth JSON. Les lectures de données restent analysées strictement comme du JSON. Le client s’identifie avec son propre User-Agent HybridCoach.

Cette incompatibilité est une piste corrigée, pas une cause confirmée sur le compte réel. Les essais sans clé valide n’ont pas reproduit le 406 : ils retournent HTTP 401. Une nouvelle tentative avec votre clé dans l’application est nécessaire pour confirmer la résolution.

Les erreurs affichent désormais le **code HTTP et l’étape** : obtention du jeton, lecture du profil, lecture des activités ou lecture du détail. Les corps des erreurs, la clé et les jetons ne sont jamais affichés. Un HTTP 403 n’est plus présenté comme la preuve d’une clé invalide. Si le 406 persiste, communiquer uniquement ce message d’erreur avec l’étape ; ne pas transmettre la clé.

## Expiration et déconnexion

La clé peut être créée pour 30, 90, 180 jours ou un an selon la documentation Garmin. À son expiration ou après sa révocation, créer une nouvelle clé et utiliser **Remplacer la clé Garmin**. Une nouvelle clé du même compte conserve les associations existantes : l’identité repose sur l’identifiant propriétaire des activités, pas sur la clé.

**Actualiser l’état** vérifie la connexion auprès de Garmin. Un compte sans activité peut se connecter ; il n’entraîne aucune suppression d’un autre compte. Un changement inattendu de profil ou de propriétaire interrompt la synchronisation et demande de reconnecter la clé.

**Déconnecter** efface la clé chiffrée locale et le jeton en mémoire, en conservant les activités du calendrier. Pour révoquer la clé et ses jetons auprès de Garmin, supprimer aussi cette clé sur la page officielle. La déconnexion locale ne réalise pas cette révocation distante.

## Données, rapprochement et suppressions

Les distances sont converties depuis les mètres, les durées depuis les secondes et la fréquence cardiaque depuis les bpm. L’allure moyenne est calculée en secondes/km. La date du calendrier est la date locale de l’activité, distincte de son instant UTC. Les mesures facultatives absentes restent absentes.

Les activités de course et de force utilisent les catégories correspondantes ; les autres sports restent dans « Autre », avec leur type Garmin consultable. Les activités multisports sont demandées sans leurs enfants pour éviter de comptabiliser le parent et chacune de ses étapes.

Le rapprochement existant est conservé : proximité de date, sport, distance, durée, nom et horaire prévu, avec validation des cas ambigus. Une séance associée garde son UUID, son programme, ses sous-objectifs et sa date prévue. Les résultats apparaissent à la date réelle ; le volume prévu reste dans sa semaine d’origine. Les activités en attente et ignorées ne sont pas comptabilisées comme résultats.

Une activité est identifiée par son identifiant Garmin et une empreinte des mesures normalisées. Une synchronisation identique ne réécrit pas les corrections locales. Les modifications distantes mettent à jour les mesures en conservant les annotations locales. Toute synchronisation incomplète est abandonnée avant la transaction d’import.

Une disparition de la liste ne suffit pas à supprimer un résultat : l’application vérifie la ressource de détail et exige un **HTTP 404**. Si le détail existe, il est récupéré. Un HTTP 401/403/429, une panne, une page dupliquée ou des données invalides interrompent l’opération sans appliquer de suppressions partielles. L’import et les décisions d’association sont transactionnels.

## Passage depuis la version 1.2

La base Room reste en **version 3** : aucun nouveau stockage SQL n’est nécessaire, aucun effacement de données n’est effectué. Les anciennes URL/clés du service ne sont jamais utilisées pour contacter Garmin et sont effacées du stockage de configuration lors de l’enregistrement réussi d’une clé personnelle ou de la déconnexion.

Si une activité déjà importée par l’ancien service est retrouvée dans le compte personnel, son identifiant Garmin commun prouve la correspondance. Les enregistrements de ce compte sont alors rattachés à l’identité personnelle dans la même transaction, en conservant les séances et leurs associations. Les anciennes empreintes sont réconciliées sans écraser une correction locale si les mesures distantes sont identiques. Sans activité commune, aucun ancien compte n’est réattribué arbitrairement ; son historique reste conservé.

Le service Python et son ancien guide sont conservés pour maintenance dans `services/garmin_bridge` et [GARMIN_BRIDGE_LEGACY.md](GARMIN_BRIDGE_LEGACY.md). Ils ne sont plus nécessaires au fonctionnement de l’application Android.

## Stockage et échanges

- Seul le domaine HTTPS `personal.connectapi.garmin.com` est utilisé pour les appels API ; l’utilisateur ne configure aucune URL. Les redirections HTTP ne sont pas suivies et les certificats sont vérifiés normalement.
- La clé personnelle et les identifiants vérifiés sont chiffrés avec AES-GCM, via une clé non exportable de l’Android Keystore. Aucune clé n’entre dans Room, les exports, les journaux ou l’état d’interface sauvegardé. Les sauvegardes Android de l’application sont désactivées.
- Le jeton d’accès reste en mémoire. Il est obtenu en transmettant la clé uniquement au point d’accès officiel, avec `Authorization: ApiKey …`, puis utilisé avec `Authorization: Bearer …` pour les lectures.
- Le jeton est renouvelé avant son expiration (24 h maximum selon Garmin) et une fois après un refus HTTP 401. Aucun mot de passe ni refresh token n’est nécessaire. Au redémarrage de l’application, un nouveau jeton est obtenu depuis la clé chiffrée.
- Les erreurs présentées ne recopient pas les réponses brutes de Garmin ni les secrets. Les réponses ne sont pas mises en cache par le client HTTP.

Les données sportives importées restent dans la base locale habituelle, consultable hors ligne. L’API personnelle est destinée à un usage individuel ; une application commerciale pour plusieurs utilisateurs relève d’un autre programme Garmin.

## Contrat officiel consulté le 22 octobre 2026

- [Création de clé et obtention du jeton](https://developer.garmin.com/gc-developer-program/personal/personal-api/).
- [FAQ : usage personnel, expiration, révocation et jetons](https://developer.garmin.com/gc-developer-program/personal/personal-faq/).
- [Schéma OpenAPI officiel](https://developer.garmin.com/gc-developer-program/personal/swagger-docs/) : liste paginée `/activitylist-service/activities/search/activities`, détail `/activity-service/activity/{activityId}` et profil `/userprofile-service/userprofile/settings`.

La documentation Swagger est rendue par JavaScript. Le contrat utilisé a été lu dans le schéma OpenAPI incorporé à cette page officielle. Le client reconnaît les deux formes documentées : `SummarizedActivityDTO` pour la liste et `ActivitySummaryDTO`/`summaryDTO` pour le détail.

## Validation

Les tests automatisés emploient des clés et activités synthétiques, un transport simulé et une base Room locale. Ils couvrent l’authentification, le renouvellement, la pagination, les unités, les dates, les erreurs, les suppressions confirmées, la rotation de clé, le chiffrement, la migration depuis le service et l’interface. Voir [VERIFICATION.md](VERIFICATION.md) pour les résultats.

Aucun compte ni clé Garmin réel n’a été fourni : l’authentification et l’import réels restent à vérifier sur le téléphone après saisie de votre propre clé. Ne jamais transmettre cette clé dans une conversation ni la versionner dans le projet.
