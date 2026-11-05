# Hybrid Coach 1.7.1 — Publications et mises à jour GitHub

**Profil sportif → À propos et mises à jour** recherche les versions stables du dépôt public douwwii/HybridCoach, affiche leurs nouveautés et télécharge l’APK. L’application contrôle son empreinte, son identité et son certificat avant de proposer l’installateur Android. L’utilisateur autorise la source si nécessaire et confirme l’installation. La recherche est manuelle et aucun compte GitHub n’est requis sur le téléphone.

La distribution utilise un build release non débogable signé avec la clé historique conservée hors Git. Les données restent locales ; Room v4 et la sauvegarde JSON sont inchangés. Les versions antérieures à 1.7 doivent recevoir une première installation manuelle compatible pour bénéficier du nouvel écran.

Le dépôt contient maintenant les scripts de préparation/publication, CHANGELOG.md, les workflows CI/release, les formulaires de signalement et les guides. Les fichiers générés restent sur disque et sont exclus des prochains commits ; les APK et archives doivent être distribués dans les Releases.

Validation locale : **167 tests Android/JVM réussis** et **7 tests Python**, compilation debug, release signé non débogable et tests instrumentés, lint debug **0 erreur, 29 avertissements et 3 informations**. Une capture du parcours de mise à jour est inspectée. Les réponses réseau et l’APK des tests sont synthétiques ; le cycle réel sur un téléphone reste à vérifier après publication.

- Version **1.7.1 / code 10**, Android API 26 minimum.
- Préparation des publications : `scripts/prepare_release.py` → `dist/v1.7.1/`.
- Guide utilisateur : `docs/MISES_A_JOUR.md`.
- Guide mainteneur et signature : `docs/PUBLICATION_GITHUB.md`.
- Vérifications : `docs/VERIFICATION.md`.

La clé privée ne fait partie d’aucun dossier de publication. L’activation de la signature GitHub Actions nécessite les secrets de dépôt et l’autorisation du mainteneur ; aucune clé de remplacement n’est générée.
