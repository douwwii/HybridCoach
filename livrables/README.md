# Livrables locaux

Les APK, archives, sommes de contrôle et rapports générés restent disponibles localement, mais ne sont plus ajoutés à Git. Les versions destinées aux utilisateurs sont regroupées dans les [publications GitHub](https://github.com/douwwii/HybridCoach/releases).

- Le dossier `dist/vX.Y.Z/` contient tous les fichiers à joindre à une publication.
- `scripts/prepare_release.py` génère ce dossier depuis un APK release signé et les sources enregistrées.
- `BILAN.md` décrit la livraison courante.
- Les rapports de CI sont conservés dans les artefacts GitHub Actions.

Voir [la procédure de publication](../docs/PUBLICATION_GITHUB.md).
