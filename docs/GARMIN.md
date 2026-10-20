# Garmin Connect — étape 1, version 1.2.0

## Ce qui est fourni

**Profil sportif → Garmin Connect** donne accès à la connexion, la double authentification, l’état, la dernière synchronisation, le nombre d’activités importées et à **Synchroniser Garmin**. Les activités ambiguës se valident depuis **Activités à examiner**. Une association peut être modifiée depuis cet écran ou le détail d’une séance importée.

L’application conserve ses fonctions locales hors ligne. Une synchronisation nécessite le service personnel décrit ci-dessous, une connexion Internet et un compte Garmin. Aucun service hébergé ni identifiant Garmin n’est préconfiguré dans l’APK.

## Architecture et choix Android

```text
Téléphone Kotlin / Compose
  GarminGateway                 contrat remplaçable
    GarminBridgeClient          HTTPS / JSON v1, aucune dépendance Python Android
  GarminImporter                transactions Room et décisions utilisateur
  GarminMatcher                 calcul pur, indépendant du réseau et de Room
  Room v3                       activités, liens uniques, historique prévu, résultats
    CoachState                  calendrier, préparation, historique, statistiques
           |
           | HTTPS + clé privée du service
           v
Service Python personnel, un utilisateur
  bridge.app                    API authentifiée, erreurs sans secrets
  bridge.provider               seul adaptateur de python-garminconnect 0.3.19
  bridge.core                   normalisation, pagination, détection des modifications/suppressions
  Vault                         SQLite chiffré avec Fernet
           |
           | HTTPS Garmin
           v
Garmin Connect
```

La version fixée de [python-garminconnect](https://pypi.org/project/garminconnect/0.3.19/) demande Python 3.12 ou plus et utilise notamment `curl_cffi`. Elle est exécutée dans un service Linux Python 3.13 fourni, plutôt que de supposer que ses dépendances natives fonctionnent dans l’APK. Le dépôt contient le code du service, son image Docker et un reverse proxy HTTPS. Les appels du SDK sont isolés dans `PythonGarminProvider`, derrière `ActivityProvider` : le remplacement par une API officielle ne touche ni le rapprochement ni la base Android. Le client Android est lui-même abstrait derrière `GarminGateway`.

`GarminWorkoutExporter` réserve le contrat de l’étape 2. Il n’existe ni implémentation d’envoi, ni bouton d’export vers Garmin, ni écriture d’entraînement dans Garmin. L’arbre `WorkoutPlan` conserve déjà répétitions, cibles et récupérations.

La bibliothèque est non officielle : un changement ou un blocage de Garmin peut nécessiter sa mise à jour. Les tests utilisent la version réelle du SDK pour vérifier les signatures de l’adaptateur, et des réponses simulées pour les authentifications et activités. Aucune authentification sur un vrai compte n’est déclarée validée.

## Installer le service sur un serveur

Prévoir un serveur Linux qui reste allumé, avec Docker + Docker Compose, accès sortant HTTPS vers Garmin, un nom DNS et les ports 80/443 accessibles pour le proxy Caddy. Un VPS ou un serveur personnel correctement joignable convient ; le service est **privé et mono-utilisateur**. Ne partagez pas une même instance entre plusieurs personnes : elles partageraient la session Garmin et les données de l’instance.

Depuis la racine du projet, sur ce serveur :

```bash
cd services/garmin_bridge
python3 -m bridge.bootstrap --domain garmin.votre-domaine.fr
docker compose up --build -d
```

Remplacez le domaine d’exemple par votre véritable domaine, dont les enregistrements DNS A/AAAA pointent vers le serveur. Le script génère **sans les afficher** une clé d’accès et une clé de chiffrement dans `secrets.env`, en permissions 0600. Ce fichier est ignoré par Git. Il n’inclut aucun mot de passe Garmin. Le bootstrap utilise seulement la bibliothèque standard Python ; Python 3.13 et les dépendances du service sont installés dans l’image.

Caddy obtient et renouvelle le certificat public si le domaine et les ports sont correctement configurés ; voir ses [conditions d’HTTPS automatique](https://caddyserver.com/docs/automatic-https). Le port interne 8000 du service n’est pas publié par Compose. Les accès Android utilisent exclusivement HTTPS avec vérification normale du certificat ; aucun contournement de certificat ni mode HTTP n’est ajouté.

Contrôlez l’état public :

```bash
curl --fail https://garmin.votre-domaine.fr/health
```

La réponse attendue est `{"ok":true}`. Ce point ne révèle aucune donnée de compte. Tous les points `/v1/*` exigent la clé privée. Les journaux d’accès applicatifs sont désactivés et les erreurs de l’API ne renvoient pas les corps de réponse du SDK, tickets SSO ou identifiants.

Sauvegardez **le volume `garmin-data` et `secrets.env` ensemble**, en lieu privé. La clé maître permet de déchiffrer les jetons et les activités stockées. Sa perte rend la session et le cache illisibles ; sa régénération change aussi l’identité de connexion. Android refuse de réimporter les mêmes identifiants Garmin sous une nouvelle identité de service, afin d’éviter les doublons. Restaurer la sauvegarde d’origine est le chemin normal de récupération. Ne lancez pas `docker compose down -v` si vous souhaitez conserver le cache.

Le service fonctionne avec **un seul worker**, car une demande de MFA contient un état temporaire en mémoire. Le code expire après cinq minutes ; après un redémarrage du service, recommencer la connexion. La session validée et ses jetons rafraîchis sont persistés chiffrés. Les sessions que le SDK ne peut pas sérialiser en jetons persistants sont refusées plutôt que présentées comme connectées.

### Depuis le téléphone

1. Installer/lancer Hybrid Coach 1.2.0.
2. Ouvrir **Profil sportif → Garmin Connect**.
3. Saisir `https://garmin.votre-domaine.fr` et la valeur `GARMIN_BRIDGE_API_KEY` de votre fichier privé `secrets.env`, puis **Enregistrer le service**. Garder la clé confidentielle ; ne pas la coller dans une URL ou une capture partagée.
4. Saisir le compte et le mot de passe Garmin, puis **Connecter Garmin**. Cocher le compte Chine uniquement si nécessaire.
5. Si demandé, saisir le code Garmin puis **Valider le code**.
6. Appuyer sur **Synchroniser Garmin**. Le premier import peut durer plus longtemps si l’historique est important.
7. Examiner les activités à confirmer et choisir une séance ou **Activité non planifiée**. Les activités en attente ne sont pas encore comptabilisées.

Le mot de passe et le code restent transitoires : ni Room, ni DataStore, ni l’état sauvegardé d’Android ne les enregistrent. Ils transitent chiffrés jusqu’au service personnel, puis vers Garmin. La clé du service et son adresse sont chiffrées avec AES-GCM et Android Keystore sur le téléphone. Les jetons Garmin restent sur le service, chiffrés avec Fernet ; aucun jeton n’est renvoyé à Android. Faites confiance au serveur et à son administrateur : ce serveur traite temporairement les identifiants de connexion et conserve un accès au compte Garmin.

**Déconnecter** efface la session du service et son éventuelle demande MFA. Les séances déjà importées restent locales. Cela n’est pas une révocation d’un jeton précédemment copié : une telle révocation se fait dans les paramètres de sécurité Garmin.

### PC, réseau local et émulateur

`localhost` sur le téléphone désigne le téléphone, pas votre PC. Une application Python démarrée sur un ordinateur n’est pas automatiquement joignable depuis Android. Pour utiliser un PC comme serveur, il doit rester allumé et être réellement joignable avec une adresse DNS, un certificat approuvé par Android et les règles réseau appropriées. Un VPN peut assurer cette connectivité, mais il faut configurer le DNS et le certificat. Cette configuration n’est pas réalisée automatiquement par le projet.

L’adresse d’hôte d’un émulateur ne constitue pas à elle seule une configuration HTTPS valable. Pour le parcours Android complet, utiliser le domaine HTTPS du service comme sur le téléphone. Le développement local du serveur peut se tester avec un client HTTP local, sans envoyer d’identifiants Garmin sur un réseau en clair.

## Synchronisation et sécurité des suppressions

Le service parcourt toutes les pages d’activités, y compris anciennes, pour détecter les éditions rétrospectives. L’identifiant Garmin reste stable ; l’empreinte SHA-256 des valeurs normalisées détermine si une activité a changé. Android ne réécrit pas les résultats d’une activité inchangée. Une révision de snapshot est persistée par compte et toute régression est refusée.

Une activité absente d’une page n’est pas considérée comme supprimée. Pour chaque identifiant connu absent du parcours complet, l’adaptateur interroge la ressource directement : seuls **404 ou 410** constituent une suppression. Une ressource encore présente est conservée avec ses dernières données connues. Une erreur réseau, d’authentification, de quota ou une pagination incohérente interrompt le snapshot, sans application partielle dans Room. Le service conserve les tombstones pour qu’un téléphone qui a manqué une synchronisation retrouve les suppressions suivantes.

L’import Android d’un snapshot et chaque décision utilisateur sont atomiques. Une association est unique des deux côtés : clé d’activité, identifiant Garmin unique et index SQL unique sur la séance liée. Les activités ignorées et les suppressions locales ne réapparaissent pas automatiquement lors d’une modification distante.

Le parcours est borné à 100 000 activités ; au-delà, il échoue explicitement sans import partiel. Les délais de réseau sont bornés. Un très gros historique ou un serveur lent peut dépasser le délai du téléphone ; dans ce cas les données locales restent intactes et une nouvelle synchronisation peut être tentée. Ce choix privilégie la vérification des modifications anciennes ; un futur protocole différentiel par curseur peut être ajouté derrière l’interface sans changer Room.

## Rapprochement et conservation des données

Le moteur compare le jour (écart maximal d’un jour), le sport, la distance, la durée, les mots du nom et l’horaire prévu facultatif. Il compare aussi les mesures locales d’une séance déjà terminée pour éviter de créer un doublon lorsqu’elle a été saisie manuellement. Une distance réelle de 11 km peut correspondre à 12 km prévus. Le seuil automatique est 0,78, avec une avance minimale de 0,12 sur le deuxième candidat ; ces scores sont des règles de classement, pas des probabilités.

Une séance active ou un sport générique **Autre sport** n’est jamais finalisé automatiquement. Deux activités réclamant la même séance sont examinées manuellement. Un choix manuel peut rechercher toutes les séances ; une séance déjà liée à une autre activité ou encore en cours est refusée. Sans candidat, une occurrence réalisée hors programme est créée avec un plan vide, sans kilométrage prévu inventé.

Pour une association, l’ID de la séance planifiée est conservé. Sa date d’affichage devient la date locale réelle Garmin ; `plannedDate` garde la date d’origine. Le plan, ses blocs et ses sous-objectifs restent en place. La date prévue facultative et les mesures Garmin sont affichées dans le détail ; les cartes portent la mention **GARMIN CONNECT**. Les exports de préparation indiquent la source et la date initiale.

Les volumes réels sont attribués à la date réelle et ceux prévus à la date initiale. Le calendrier, l’historique, les préparations et les statistiques observent le même `CoachState`. Les statistiques de course incluent les activités de course ; les totaux toutes activités comptent aussi les autres sports et leurs durées. Les charges et séries de musculation ne sont pas inventées à partir d’un simple résumé d’activité Garmin.

Une nouvelle mesure Garmin remplace les mesures réelles correspondantes dans le résultat, en conservant notes, RPE, fatigue, ressenti et séries saisis localement. Une synchronisation inchangée conserve aussi les corrections locales des mesures. Lors d’une dissociation, la séance planifiée retrouve sa date, son statut et ses anciennes mesures ; l’activité passe en attente sans créer immédiatement un deuxième résultat. Lors d’une suppression distante, une occurrence générée hors programme est retirée, tandis qu’une séance initialement planifiée est restaurée. Le résumé distant supprimé reste disponible dans Room pour l’historique technique ; il n’est plus compté.

## API et évolutivité

Tous les points ci-dessous, sauf `/health`, utilisent `Authorization: Bearer <clé privée>` et `Cache-Control: no-store` :

| Route | Fonction |
|---|---|
| `GET /health` | disponibilité sans données privées |
| `GET /v1/status` | session enregistrée et identité opaque du compte |
| `POST /v1/login` | `email`, `password`, `isCn` ; connexion ou challenge MFA |
| `POST /v1/mfa` | `challengeId`, `code` ; fin de connexion |
| `DELETE /v1/session` | suppression de la session du service |
| `POST /v1/sync` | snapshot normalisé complet, révision et identifiants supprimés |

Les valeurs utilisent mètres, secondes, secondes/km, bpm et mètres de dénivelé positif. Le début absolu est ISO UTC ; `localDateTime` préserve le jour et l’heure Garmin de l’activité, sans les convertir au fuseau du serveur. Les champs absents restent `null`. Les erreurs exposent seulement un code stable (`AUTH_REQUIRED`, `MFA_EXPIRED`, `RATE_LIMIT`, `BUSY`, `INCOMPLETE`, etc.).

Room passe de 2 à 3 avec `MIGRATION_2_3`, en conservant la migration 1 → 2. Ajouts : date initiale, heure prévue, source, tables `garminActivities` et `garminSync`. Les schémas 1, 2 et 3 sont versionnés. Les sauvegardes d’une séance et de son résultat antérieurs à l’association servent à la restauration ; elles ne contiennent aucun secret d’authentification.

## Validation reproductible

Depuis la racine :

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
python3 -m venv services/garmin_bridge/.venv
services/garmin_bridge/.venv/bin/pip install -r services/garmin_bridge/requirements-test.txt
PYTHONPATH=services/garmin_bridge services/garmin_bridge/.venv/bin/python -m pytest services/garmin_bridge/tests
```

Les tests couvrent le classement, les ambiguïtés, les collisions, les duplications, les changements distants, les suppressions, la réattribution de semaine, la conservation des résultats manuels et la migration réelle d’un fichier SQLite v2. Un parcours Compose confirme une activité incertaine et ouvre la séance avec sa provenance Garmin. Les tests Python vérifient les routes réelles, l’authentification du service, la MFA, le chiffrement, la pagination et les erreurs simulées. Voir `VERIFICATION.md` pour les résultats réellement exécutés.

L’image de service a été construite et démarrée avec Podman (format Docker), et son API HTTP a été vérifiée avec et sans clé. La configuration Caddy a été validée avec l’image 2.10.2. Le déploiement Compose sur votre serveur, le certificat public et l’authentification sur un vrai compte Garmin restent à vérifier sur les environnements configurés ; ils ne sont pas déduits de ces tests.
