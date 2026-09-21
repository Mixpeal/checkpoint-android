#!/usr/bin/env python3
"""
Train the situation heads: five typed questions asked of ONE utterance.

This is the part that is not retrieval. The model does not fetch a paragraph --
it extracts typed facts (which agency, is money demanded, what channel, is
detention threatened, is a document held) and a human-written rule layer
composes them, each rule carrying a citation.

Run:  python training/train_situation.py
"""
import csv, json, math, pathlib
import numpy as np
from sentence_transformers import SentenceTransformer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold
from scipy.optimize import minimize_scalar

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODEL, PREFIX = "intfloat/multilingual-e5-small", "query: "

spec = json.load(open(ROOT/"packs"/"situation.json", encoding="utf-8"))
rows = [r for r in csv.DictReader(open(ROOT/"training"/"situation.csv", encoding="utf-8"))
        if r["text"].strip()]
print(f"{len(rows)} utterances")

m = SentenceTransformer(MODEL)
X = m.encode([PREFIX + r["text"] for r in rows], normalize_embeddings=True).astype(np.float64)

def temp_fit(Z, y):
    def nll(t):
        Zt = Z/t; Zt -= Zt.max(1, keepdims=True)
        P = np.exp(Zt); P /= P.sum(1, keepdims=True)
        return -np.log(np.clip(P[np.arange(len(y)), y], 1e-12, 1)).mean()
    return float(minimize_scalar(nll, bounds=(0.05, 20), method="bounded").x)

out = {"model": MODEL, "prefix": PREFIX, "dim": int(X.shape[1]), "heads": {}}
print(f"\n{'question':<12}{'type':>8}{'classes':>9}{'OOF acc':>9}{'ECE':>7}")
print("-"*47)

for q in spec["questions"]:
    col = q["id"]
    if q["type"] == "choice":
        opts = q["options"]; y = np.array([opts.index(r[col]) for r in rows])
    else:
        opts = ["no", "yes"]; y = np.array([int(r[col]) for r in rows])

    k = min(4, int(np.bincount(y).min()))
    acc = ece = float("nan"); T = 1.0
    if k >= 2:
        Z = np.zeros((len(y), len(opts)))
        for tr, te in StratifiedKFold(n_splits=k, shuffle=True, random_state=0).split(X, y):
            c = LogisticRegression(C=300., max_iter=8000).fit(X[tr], y[tr])
            d = c.decision_function(X[te])
            if d.ndim == 1: d = np.c_[-d, d]
            Z[np.ix_(te, c.classes_)] = d
        T = temp_fit(Z, y)
        Zt = Z/T; Zt -= Zt.max(1, keepdims=True)
        P = np.exp(Zt); P /= P.sum(1, keepdims=True)
        acc = (P.argmax(1) == y).mean()
        conf = P.max(1); hit = (P.argmax(1) == y).astype(float)
        ece = sum(((conf > b/10) & (conf <= (b+1)/10)).mean() *
                  abs(hit[(conf > b/10) & (conf <= (b+1)/10)].mean() -
                      conf[(conf > b/10) & (conf <= (b+1)/10)].mean())
                  for b in range(10) if ((conf > b/10) & (conf <= (b+1)/10)).sum())

    clf = LogisticRegression(C=300., max_iter=10000).fit(X, y)
    W = np.zeros((len(opts), X.shape[1])); B = np.zeros(len(opts))
    if clf.coef_.shape[0] == 1:
        # Binary case: sklearn stores ONE weight vector for the positive class.
        # Copying it into both rows makes the two logits identical and the
        # softmax returns exactly 0.5 every time. Split it into +/- instead.
        W[0], B[0] = -clf.coef_[0] / 2, -clf.intercept_[0] / 2
        W[1], B[1] = +clf.coef_[0] / 2, +clf.intercept_[0] / 2
    else:
        for r_, cls in enumerate(clf.classes_):
            W[cls], B[cls] = clf.coef_[r_], clf.intercept_[r_]
    out["heads"][col] = {"options": opts, "temperature": round(T, 4),
                         "W": [[round(v, 6) for v in r_] for r_ in W.tolist()],
                         "b": [round(v, 6) for v in B.tolist()]}
    print(f"{col:<12}{q['type']:>8}{len(opts):>9}{acc:>9.3f}{ece:>7.3f}")

p = ROOT/"packs"/"situation_head.json"
json.dump(out, open(p, "w"), separators=(",", ":"))
print(f"\nwrote {p}  ({p.stat().st_size/1024:.1f} KB)")
