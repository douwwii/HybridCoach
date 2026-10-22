> Archive de l’intégration 1.2 : l’application Android 1.3 utilise directement l’API personnelle officielle Garmin. Ce service n’est plus requis. Voir [le guide actuel](../../docs/GARMIN.md) et [l’ancien guide du service](../../docs/GARMIN_BRIDGE_LEGACY.md).

# Service Garmin personnel

Voir [le guide complet d’installation, Android et sécurité](../../docs/GARMIN_BRIDGE_LEGACY.md).

Ce service mono-utilisateur fournit uniquement l’import d’activités via python-garminconnect. Aucun entraînement n’est envoyé à Garmin. Les identifiants Garmin ne sont pas conservés ; les jetons et le cache sont chiffrés dans `/data/garmin.db`. Garder `secrets.env` privé et sauvegardé avec le volume de données.

```bash
python3 -m bridge.bootstrap --domain garmin.votre-domaine.fr
docker compose up --build -d
```

Un domaine DNS, des ports 80/443 joignables et un serveur restant allumé sont nécessaires pour cette configuration HTTPS. L’APK ne contient pas d’URL de service prédéfinie.
