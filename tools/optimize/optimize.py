"""Find feasible circuit designs with Optuna and GeckoCIRCUITS."""

import argparse
import csv
import math
from pathlib import Path
import sys

import matplotlib
matplotlib.use("Agg")
from matplotlib import pyplot as plt
import optuna

from .batch import BatchEvaluator, SimulationFailed, connect
from .problem import load_problem


def optimize(problem, evaluator):
    objectives = problem.objectives
    if not objectives:
        raise ValueError("Specify at least one optimization objective.")
    options = {"seed": problem.seed}
    if problem.constraints:
        options["constraints_func"] = lambda trial: trial.user_attrs["violations"]
    if len(objectives) == 1:
        sampler = optuna.samplers.TPESampler(**options)
    else:
        sampler = optuna.samplers.NSGAIISampler(
            population_size=max(2, min(20, problem.trials // 5)), **options
        )
    study = optuna.create_study(
        directions=[objective.direction for objective in objectives], sampler=sampler
    )
    for offset in range(0, problem.trials, problem.batch_size):
        trials = [study.ask() for _ in range(min(problem.batch_size, problem.trials - offset))]
        samples = [
            {name: trial.suggest_float(name, bound.min, bound.max)
             for name, bound in problem.parameters.items()}
            for trial in trials
        ]
        for trial, sample, result in zip(trials, samples, evaluator.evaluate(samples), strict=True):
            if isinstance(result, SimulationFailed):
                print(result, file=sys.stderr)
                study.tell(trial, state=optuna.trial.TrialState.FAIL)
                continue
            values = sample | result
            violations = [
                math.nextafter(value, math.inf)
                for rule in problem.constraints for value in rule.violations(values)
            ]
            trial.set_user_attr("violations", violations)
            trial.set_user_attr("metrics", result)
            study.tell(trial, [objective.score(values) for objective in objectives])
    front = sorted(study.best_trials, key=lambda trial: (trial.values, trial.number))
    if not front:
        raise RuntimeError("The study found no feasible designs.")
    return front


def write_front(output, problem, front):
    output.mkdir(parents=True, exist_ok=True)
    with (output / "pareto.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, ["trial", *problem.parameters, *problem.metrics])
        writer.writeheader()
        for trial in front:
            writer.writerow({"trial": trial.number} | trial.params | trial.user_attrs["metrics"])

    def label(objective):
        if objective.target is None:
            return objective.metric
        return f"Absolute error from {objective.target:g} ({objective.metric})"

    columns = list(zip(*(trial.values for trial in front), strict=True))
    if len(columns) == 1:
        figure, axis = plt.subplots()
        axis.scatter([trial.number for trial in front], columns[0])
        axis.set(xlabel="Trial", ylabel=label(problem.objectives[0]))
    else:
        pairs = [(a, b) for b in range(1, len(columns)) for a in range(b)]
        figure, axes = plt.subplots(len(pairs), 1, figsize=(7, 4 * len(pairs)), squeeze=False)
        for axis, (a, b) in zip(axes[:, 0], pairs, strict=True):
            axis.scatter(columns[a], columns[b])
            axis.set(xlabel=label(problem.objectives[a]), ylabel=label(problem.objectives[b]))
    figure.savefig(output / "pareto.png", bbox_inches="tight")
    plt.close(figure)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("problem", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    problem = load_problem(args.problem)
    optuna.logging.set_verbosity(optuna.logging.WARNING)
    with connect() as client:
        front = optimize(problem, BatchEvaluator(client, problem))
    write_front(args.output, problem, front)
    print(f"{len(front)} feasible Pareto trials. Results: {args.output}")


if __name__ == "__main__":
    main()
