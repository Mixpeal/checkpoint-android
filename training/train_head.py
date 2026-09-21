#!/usr/bin/env python3
"""
Turn your phrasings into a calibrated claim-matching head.

Uses the exact recipe measured earlier in this project:
  multilingual-e5-small  ->  L2-normalised 384-d embedding
  multinomial logistic regression
  ONE temperature fitted on out-of-fold logits

Note: a per-claim temperature is degenerate at this scale. Each class holds a
single claim, so fitting T on that class's own examples sees only one label and
collapses toward maximal confidence. Per-claim THRESHOLDS carry the safety
control instead -- that is the lever that matters anyway.

Outputs data/claim_head.json, which the browser loads. The browser runs the same
model and the same "query: " prefix, so embeddings match this script.

Run:  python training/train_head.py
"""
import argparse, csv, json, math, pathlib, sys
import numpy as np
from collections import Counter
from sentence_transformers import SentenceTransformer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold
from scipy.optimize import minimize_scalar

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODEL = "intfloat/multilingual-e5-small"
PREFIX = "query: "                      # MUST match app.js

ap = argparse.ArgumentParser()
ap.add_argument("--claims",    default="packs/claims.json")
ap.add_argument("--phrasings", default="training/phrasings.csv")
ap.add_argument("--out",       default="packs/claim_head.json")
A = ap.parse_args()

rows = [r for r in csv.DictReader(open(ROOT/A.phrasings, encoding="utf-8"))
        if r["text"].strip() and not r["text"].strip().startswith("TODO")]
if not rows:
    sys.exit(f"No usable phrasings in {A.phrasings} (TODO rows are skipped).")

claims = json.load(open(ROOT/A.claims, encoding="utf-8"))
ids = [c["id"] for c in claims["claims"]]
IDX = {cid: i for i, cid in enumerate(ids)}
unknown = {r["claim_id"] for r in rows} - set(IDX)
if unknown:
    sys.exit(f"phrasings.csv references unknown claim_id(s): {sorted(unknown)}")

y = np.array([IDX[r["claim_id"]] for r in rows])
counts = Counter(y)
print(f"{len(rows)} phrasings across {len(counts)}/{len(ids)} claims")
thin = [ids[c] for c, n in counts.items() if n < 8]
if thin:
    print(f"  WARNING under 8 examples: {thin}  (10+/claim was the measured floor)")

print(f"embedding with {MODEL} …")
m = SentenceTransformer(MODEL)
X = m.encode([PREFIX + r["text"] for r in rows], normalize_embeddings=True,
             batch_size=64, show_progress_bar=False).astype(np.float64)

def softmax_T(Z, T):
    Z = Z / T; Z = Z - Z.max(1, keepdims=True)
    e = np.exp(Z); return e / e.sum(1, keepdims=True)

def fit_T(Z, yy):
    def nll(t):
        P = softmax_T(Z, t)
        return -np.log(np.clip(P[np.arange(len(yy)), yy], 1e-12, 1)).mean()
    return float(minimize_scalar(nll, bounds=(0.05, 20), method="bounded").x)

# ---- honest out-of-fold estimate, WITH the calibration that ships ----------
def ece(P, yy, bins=10):
    c = P.max(1); a = (P.argmax(1) == yy).astype(float); t = 0.
    for b in range(bins):
        lo, hi = b/bins, (b+1)/bins
        m = (c > lo) & (c <= hi) if b else (c >= 0) & (c <= hi)
        if m.sum(): t += m.mean()*abs(a[m].mean()-c[m].mean())
    return t

n_classes = len(ids)
k = min(5, min(counts.values()))
T_global = 1.0
if k >= 2:
    oof = np.zeros((len(y), n_classes)); oofz = np.zeros((len(y), n_classes))
    for tr, te in StratifiedKFold(n_splits=k, shuffle=True, random_state=0).split(X, y):
        c_ = LogisticRegression(C=300., max_iter=6000).fit(X[tr], y[tr])
        oof[np.ix_(te, c_.classes_)] = c_.predict_proba(X[te])
        Zf = c_.decision_function(X[te])
        if Zf.ndim == 1: Zf = np.c_[-Zf, Zf]
        oofz[np.ix_(te, c_.classes_)] = Zf
    # ONE temperature, fitted on the out-of-fold logits -- this is what ships.
    # A per-claim temperature is degenerate when each class has one claim:
    # every label in the subset is that class, so it collapses toward T=0.
    T_global = fit_T(oofz, y)
    P_cal = softmax_T(oofz, T_global)
    acc = (oof.argmax(1) == y).mean()
    print(f"\n{k}-fold out-of-fold: accuracy={acc:.3f}")
    print(f"  ECE uncalibrated={ece(oof,y):.3f}   ECE after T={T_global:.2f}: {ece(P_cal,y):.3f}")
    print(f"  mean confidence  {oof.max(1).mean():.3f}  ->  {P_cal.max(1).mean():.3f}")
    print(f"\n{'claim':<28}{'n':>4}{'recall':>8}{'conf':>8}{'abstains':>10}")
    for i, cid in enumerate(ids):
        msk = y == i
        if not msk.sum(): continue
        thr = claims["claims"][i].get("threshold", 0.8)
        conf = P_cal[msk].max(1)
        print(f"{cid:<28}{msk.sum():>4}{(P_cal[msk].argmax(1)==i).mean():>8.3f}"
              f"{conf.mean():>8.3f}{(conf<thr).mean():>10.0%}")
    hi = [ids[i] for i in range(n_classes)
          if (y==i).sum() and (P_cal[y==i].argmax(1)==i).mean() < 0.8]
    if hi: print(f"\n  LOW RECALL, needs more phrasings or is too close to a neighbour: {hi}")
else:
    print("\nToo few examples per claim for cross-validation. Metrics skipped.")

# ---- out-of-domain floor ----------------------------------------------------
# A softmax over N claims always sums to 1, so unrelated input still produces a
# confident-looking winner. Thresholds alone cannot catch that. So we also keep
# a centroid per claim and require the query to actually LOOK like something in
# the pack before we are willing to speak.
C = np.zeros((len(ids), X.shape[1]))
for i in range(len(ids)):
    msk = y == i
    if msk.sum():
        v = X[msk].mean(0); C[i] = v / (np.linalg.norm(v) + 1e-12)
in_dom = (X @ C.T).max(1)
# Calibrating this off the in-domain tail was wrong: it assumes real questions
# look like training examples. Measured, they sit BELOW the training tail while
# genuinely unrelated text sits far lower. So set the floor from actual
# negatives when they exist, and leave headroom below the in-domain minimum.
neg_path = ROOT/"training"/"negatives.txt"
if neg_path.exists():
    negs = [l.strip() for l in neg_path.read_text(encoding="utf-8").splitlines()
            if l.strip() and not l.startswith("#")]
    Sn = (m.encode([PREFIX+t for t in negs], normalize_embeddings=True).astype(np.float64) @ C.T).max(1)
    # Centroid distance only helps while negatives sit clearly below in-domain.
    # Once the pack spans a whole domain the bands overlap and the floor becomes
    # noise -- at which point one-vs-rest already answers "none of these apply",
    # which a softmax never could. Emit the floor only when it truly separates.
    if Sn.max() < in_dom.min() - 0.01:
        OOD_FLOOR = float((Sn.max() + in_dom.min()) / 2)
        print(f"\nOOD floor {OOD_FLOOR:.3f}  (negatives max {Sn.max():.3f} < in-domain min {in_dom.min():.3f})")
    else:
        OOD_FLOOR = None
        print(f"\nOOD floor DISABLED. Negatives ({Sn.max():.3f}) overlap in-domain ({in_dom.min():.3f}).")
        print("  one-vs-rest thresholds carry rejection instead.")
else:
    OOD_FLOOR = None
    print("\nNo training/negatives.txt. OOD floor disabled, one-vs-rest carries rejection.")

# ---- final head on all data ------------------------------------------------
clf = LogisticRegression(C=300., max_iter=8000).fit(X, y)
W = np.zeros((len(ids), X.shape[1])); B = np.zeros(len(ids))
for row, cls in enumerate(clf.classes_):
    W[cls] = clf.coef_[row] if clf.coef_.shape[0] > 1 else clf.coef_[0]
    B[cls] = clf.intercept_[row] if len(clf.intercept_) > 1 else clf.intercept_[0]

# ---- one-vs-rest: each claim judged independently ---------------------------
# A softmax forces exactly one winner, so a compound question ("they want my
# phone AND they say I must follow them") can only ever surface one answer.
# One-vs-rest lets every checked claim answer for itself.
Wo = np.zeros((len(ids), X.shape[1])); Bo = np.zeros(len(ids))
ovr_auc = []
for i in range(len(ids)):
    yi = (y == i).astype(int)
    if yi.sum() == 0 or yi.sum() == len(yi):
        continue
    c1 = LogisticRegression(C=1., max_iter=6000, class_weight="balanced").fit(X, yi)
    Wo[i] = c1.coef_[0]; Bo[i] = c1.intercept_[0]
    if k >= 2:
        oof_i = np.zeros(len(yi))
        for tr, te in StratifiedKFold(n_splits=k, shuffle=True, random_state=0).split(X, yi):
            cc = LogisticRegression(C=1., max_iter=6000, class_weight="balanced").fit(X[tr], yi[tr])
            oof_i[te] = cc.predict_proba(X[te])[:, 1]
        hit = (oof_i[yi == 1] > 0.5).mean()
        ovr_auc.append((ids[i], hit, float(oof_i[yi == 0].max())))
if ovr_auc:
    print(f"\none-vs-rest (independent per claim)")
    print(f"{'claim':<28}{'recall@.5':>11}{'worst false +':>15}")
    for cid, hit, fp in ovr_auc:
        print(f"{cid:<28}{hit:>11.2f}{fp:>15.2f}")

out = {
    "model": MODEL, "prefix": PREFIX, "dim": int(X.shape[1]),
    "pack_id": claims["pack_id"], "pack_version": claims["pack_version"],
    "claim_ids": ids,
    "temperature": round(T_global, 4),
    # thresholds deliberately NOT exported: they are editorial policy, read at
    # runtime from claims.json so retuning never requires retraining.
    "W": [[round(v, 6) for v in r] for r in W.tolist()],
    "b": [round(v, 6) for v in B.tolist()],
    "centroids": [[round(v, 6) for v in r] for r in C.tolist()],
    "Wo": [[round(v, 6) for v in r] for r in Wo.tolist()],
    "bo": [round(v, 6) for v in Bo.tolist()],
    "ood_floor": (round(OOD_FLOOR, 4) if OOD_FLOOR is not None else None),
}
p = ROOT/A.out
json.dump(out, open(p, "w"), separators=(",", ":"))
print(f"\nwrote {p}  ({p.stat().st_size/1024:.1f} KB)")
