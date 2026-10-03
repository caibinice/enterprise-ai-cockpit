# Parking Birmingham source

`parking-birmingham.csv` is the original downloaded CSV (not project parking data).

- Creator: Daniel Stolfi / Birmingham City Council / NCP.
- Citation: Stolfi, D. (2017). Parking Birmingham [Dataset]. UCI Machine Learning Repository. DOI: [10.24432/C51K5Z](https://doi.org/10.24432/C51K5Z).
- Download: <https://archive.ics.uci.edu/static/public/482/parking%2Bbirmingham.zip>.
- Dataset page: <https://archive.ics.uci.edu/dataset/482/parking%2Bbirmingham>.
- UCI licence: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The dataset description also identifies its original release as UK Open Government Licence. Attribution is retained for both descriptions; no endorsement is implied.
- CSV SHA-256: `f1e28b9c697769a1a05cd20148241b60b1a73fee4907611163efc6fd9d12770a`.

Changes in derived records: reject invalid capacity/occupancy samples; aggregate weekday/weekend 15-minute occupancy ratios; scale to three simulated zones; generate missing night hours, annual variation, vehicle aliases, arrival/departure transitions and a fictional charging ledger with a fixed seed. None of the derived dates, transactions or hospital policies are real observations. The generator and manifest describe each transformation.
