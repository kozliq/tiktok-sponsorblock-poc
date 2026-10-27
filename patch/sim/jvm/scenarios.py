"""Пишет сценарии autoskip_sim.py в текст для JVM-прогона: python3 scenarios.py 10000 > scenarios.txt"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import autoskip_sim as A  # noqa: E402

for n in range(int(sys.argv[1])):
    dur, segs, speed, lag, seeks = A.scenario(n)
    print(f"{n} {dur} {speed} {lag} | " + ";".join(f"{s},{e},{u}" for s, e, u in segs)
          + " | " + ";".join(f"{t},{to}" for t, to in seeks))
