"""Normal-neighbour novelty scores with training-only robust scaling.

Unlike tree path lengths, distance continues to grow outside the observed
normal range. No abnormal labels or fixed temperature limits are fitted here.
"""

import numpy as np
from sklearn.neighbors import NearestNeighbors


class NormalDistance:
    def __init__(self, neighbors=20):
        self.neighbors = neighbors

    def fit(self, values):
        self.center = np.median(values, axis=0)
        iqr = np.quantile(values, 0.75, axis=0) - np.quantile(values, 0.25, axis=0)
        # Binary/mostly constant door columns must not get near-infinite weight.
        self.scale = np.where(iqr > 1e-9, iqr, np.maximum(np.ptp(values, axis=0), 1.0))
        self.normal_values = values.copy()
        self.index = NearestNeighbors(n_neighbors=self.neighbors, n_jobs=1).fit(self.transform(values))
        # X=None excludes each training observation itself from its neighbours.
        self.training_scores = self.index.kneighbors()[0].mean(axis=1)
        return self

    def transform(self, values):
        scaled = (values - self.center) / self.scale
        if not np.isfinite(scaled).all():
            raise ValueError("距离特征溢出，请检查输入读数")
        return scaled

    def score(self, values):
        return self.index.kneighbors(self.transform(values))[0].mean(axis=1)

    def references(self, values):
        indices = self.index.kneighbors(self.transform(values), return_distance=False)
        neighbors = self.normal_values[indices]
        return np.quantile(neighbors, 0.05, axis=1), np.quantile(neighbors, 0.95, axis=1)
