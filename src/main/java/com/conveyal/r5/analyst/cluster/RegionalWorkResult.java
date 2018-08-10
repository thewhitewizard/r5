package com.conveyal.r5.analyst.cluster;

/**
 * This is the model class used to report accessibility indicators to the backend/broker
 * We report accessibility for a particular travel time cutoff, with travel time defined as a particular percentile.
 * So the rows are the percentiles, and the columns are the accessibility values for particular cutoffs of that percentile of travel time.
 * There are also more cutoffs than percentiles, so given Java's 2D array representation this is more efficient.
 * A particular result value should be keyed on (destinationOpportunityDataset, percentile, cutoff).
 */
public class RegionalWorkResult {

    public static int[] CUTOFFS = new int[] {15,30,45,60,75,90,105,120}; // Cutoffs in 15-minute increments to 2
    // hours;
    public String jobId;
    public int taskId;
    public double[] percentiles;
    public int[][][] accessibilityValues; // TODO Should this be floating point?

    // TODO add a way to signal that an error occurred when processing this task.
    // public String errors;
    // List all grids, percentiles, and travel time cutoffs? That should be in the job itself.

    /** Trivial no-arg constructor for deserialization. */
    public RegionalWorkResult () {};

    public RegionalWorkResult(RegionalTask task){
        this.jobId = task.jobId;
        this.taskId = task.taskId;
        this.percentiles = task.percentiles;
        this.accessibilityValues = new int [task.destinationKeys.size()][percentiles.length][CUTOFFS.length];
    }

    public RegionalWorkResult(String jobId, int taskId, int nGrids, int nPercentiles, int nTravelTimeCutoffs) {
        this.jobId = jobId;
        this.taskId = taskId;
        // The array values will default to zero, which is what we want for accessibility
        this.accessibilityValues = new int[nGrids][nPercentiles][nTravelTimeCutoffs];
    }

    /**
     * Increment the accessibility indicator value for the given grid, cutoff, and percentile
     * by the given number of opportunities. This is called repeatedly to accumulate reachable
     * destinations into different indicator values.
     */
    public void incrementAccessibility (int gridIndex, int cutoffIndex, int percentileIndex, double amount) {
        accessibilityValues[gridIndex][cutoffIndex][percentileIndex] += amount;
    }

}
