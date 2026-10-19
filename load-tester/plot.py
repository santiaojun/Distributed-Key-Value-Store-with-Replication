import pandas as pd
import matplotlib.pyplot as plt
import os
from pathlib import Path

RESULTS_DIR = Path(os.environ.get("RESULTS_DIR", "results/yunhong-local"))
PLOTS_DIR = Path(os.environ.get("PLOTS_DIR", RESULTS_DIR / "plots"))

# ── Load all CSV files by configuration prefix ───────────────
configs = {
    'w5r1':       'W=5 R=1',
    'w1r5':       'W=1 R=5',
    'w3r3':       'W=3 R=3',
    'leaderless': 'Leaderless',
}

data = {}
for prefix, label in configs.items():
    files = sorted(RESULTS_DIR.rglob(f"{prefix}_results_*.csv"))
    if files:
        df = pd.concat([pd.read_csv(f) for f in files], ignore_index=True)
        data[label] = df
        print(f"Loaded {len(df)} rows for {label}")

os.makedirs(PLOTS_DIR, exist_ok=True)

# ── Chart 1: Separate Read and Write latency plots per config ─
for label, df in data.items():
    safe = label.replace(' ', '_').replace('=', '')

    reads  = df[df['type'] == 'READ']['latency_ms']
    writes = df[df['type'] == 'WRITE']['latency_ms']

    # 1a — Read latency
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.hist(reads, bins=50, color='steelblue', edgecolor='white')
    ax.set_title(f'Read Latency Distribution — {label}')
    ax.set_xlabel('Latency (ms)')
    ax.set_ylabel('Count')
    plt.tight_layout()
    fname = PLOTS_DIR / f"read_latency_{safe}.png"
    plt.savefig(fname, dpi=150)
    plt.close()
    print(f"Saved {fname}")

    # 1b — Write latency
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.hist(writes, bins=50, color='tomato', edgecolor='white')
    ax.set_title(f'Write Latency Distribution — {label}')
    ax.set_xlabel('Latency (ms)')
    ax.set_ylabel('Count')
    plt.tight_layout()
    fname = PLOTS_DIR / f"write_latency_{safe}.png"
    plt.savefig(fname, dpi=150)
    plt.close()
    print(f"Saved {fname}")

    # 1c — Combined (kept for quick reference)
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4))
    fig.suptitle(f'Latency Distribution — {label}')
    ax1.hist(reads,  bins=50, color='steelblue', edgecolor='white')
    ax1.set_title('READ Latency'); ax1.set_xlabel('Latency (ms)'); ax1.set_ylabel('Count')
    ax2.hist(writes, bins=50, color='tomato',    edgecolor='white')
    ax2.set_title('WRITE Latency'); ax2.set_xlabel('Latency (ms)'); ax2.set_ylabel('Count')
    plt.tight_layout()
    fname = PLOTS_DIR / f"latency_{safe}.png"
    plt.savefig(fname, dpi=150)
    plt.close()
    print(f"Saved {fname}")

# ── Chart 2: Time interval between write and read of same key
for label, df in data.items():
    df_sorted = df.sort_values(['key', 'timestamp_ms'])
    intervals = []

    for key, group in df_sorted.groupby('key'):
        group = group.reset_index(drop=True)
        for i in range(1, len(group)):
            prev = group.iloc[i - 1]
            curr = group.iloc[i]
            # Find consecutive write -> read pairs on the same key
            if prev['type'] == 'WRITE' and curr['type'] == 'READ':
                intervals.append(curr['timestamp_ms'] - prev['timestamp_ms'])

    if intervals:
        fig, ax = plt.subplots(figsize=(8, 4))
        ax.hist(intervals, bins=50, color='mediumseagreen', edgecolor='white')
        ax.set_title(f'Read-after-Write Interval - {label}')
        ax.set_xlabel('Time Interval (ms)')
        ax.set_ylabel('Count')
        plt.tight_layout()
        fname = PLOTS_DIR / f"interval_{label.replace(' ', '_').replace('=','')}.png"
        plt.savefig(fname, dpi=150)
        plt.close()
        print(f"Saved {fname}")

# ── Chart 3: Stale read count comparison across configurations
stale_counts = {}
for label, df in data.items():
    reads = df[df['type'] == 'READ']
    stale_counts[label] = reads['stale'].sum()

fig, ax = plt.subplots(figsize=(8, 4))
ax.bar(stale_counts.keys(), stale_counts.values(), color='orange', edgecolor='white')
ax.set_title('Stale Read Count by Configuration')
ax.set_xlabel('Configuration')
ax.set_ylabel('Stale Read Count')
plt.tight_layout()
plt.savefig(PLOTS_DIR / "stale_reads_comparison.png", dpi=150)
plt.close()
print(f"Saved {PLOTS_DIR / 'stale_reads_comparison.png'}")

print(f"\nAll plots saved to {PLOTS_DIR}/")
