package com.conveyal.r5.analyst;

import com.beust.jcommander.ParameterException;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.conveyal.r5.analyst.cluster.TimeGrid;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.profile.FastRaptorWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Given a bunch of travel times from an origin to a single destination grid cell, this collapses that long list into a
 * limited number of percentiles, then optionally accumulates that destination's opportunity count into the appropriate
 * cumulative opportunities accessibility indicators at that origin.
 */
public class TravelTimeReducer {

    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeReducer.class);

    /** Travel time results for a whole grid of destinations. May be null if we're only recording accessibility. */
    private TimeGrid timeGrid = null;

    private RegionalWorkResult accessibilityResult = null;

    private final boolean retainTravelTimes;

    private final boolean calculateAccessibility;

    private final int[] percentileIndexes;

    private final int nPercentiles;

    private final int timesPerDestination;

    private double[][] targetValues = null;

    /**
     * Knowing the number of times that will be provided per destination and holding that constant allows us to
     * pre-compute and cache the positions within the sorted array at which percentiles will be found.
     */
    public TravelTimeReducer (AnalysisTask task) {

        this.timesPerDestination = task.getMonteCarloDrawsPerMinute() * task.getTimeWindowLengthMinutes();
        this.nPercentiles = task.percentiles.length;

        // We pre-compute the indexes at which we'll find each percentile in a sorted list of the given length.
        this.percentileIndexes = new int[nPercentiles];
        for (int p = 0; p < nPercentiles; p++) {
            percentileIndexes[p] = findPercentileIndex(timesPerDestination, task.percentiles[p]);
        }

        // Decide whether we want to retain travel times to all destinations for this origin.
        retainTravelTimes = task instanceof TravelTimeSurfaceTask || task.makeStaticSite;
        if (retainTravelTimes) {
            timeGrid = new TimeGrid(task.zoom, task.west, task.north, task.width, task.height, task.percentiles.length);
        }

        // Decide whether we want to calculate cumulative opportunities accessibility indicators for this origin.
        calculateAccessibility = task instanceof RegionalTask && ((RegionalTask)task).targetValues != null; //
        // TODO check this conditional
        if (calculateAccessibility) {
            accessibilityResult = new RegionalWorkResult((RegionalTask)task);
            this.targetValues = ((RegionalTask)task).targetValues;
        }
    }


    /**
     * Compute the index into a sorted list of N elements at which a particular percentile will be found.
     * Our method does not interpolate, it always reports a value actually appearing in the list of elements.
     * That is to say, the percentile will be found at an integer-valued index into the sorted array of elements.
     * The definition of a non-interpolated percentile is as follows: the smallest value in the list such that no more
     * than P percent of the data is strictly less than the value and at least P percent of the data is less than or
     * equal to that value. The 100th percentile is defined as the largest value in the list.
     * See https://en.wikipedia.org/wiki/Percentile#Definitions
     *
     * We scale the interval between the beginning and end elements of the array (the min and max values).
     * In an array with N values this interval is N-1 elements. We should be scaling N-1, which makes the result
     * always defined even when using a high percentile and low number of elements. Previously, this caused
     * an error when requesting the 95th percentile when times.length = 1 (or any length less than 10).
     */
    private static int findPercentileIndex(int nElements, double percentile) {
        // The definition uses ceiling for one-based indexes but we use zero-based indexes so we can truncate.
        // FIXME truncate rather than rounding.
        // TODO check the difference in results caused by using the revised formula in both single and regional analyses.
        return (int) Math.round(percentile / 100 * nElements);
    }

    /**
     * Given a list of travel times of the expected length, extract the requested percentiles. Either the extracted
     * percentiles or the resulting accessibility values (or both) are then stored.
     * WARNING: this method destructively sorts the supplied times in place.
     * Their positions in the array will no longer correspond to the raptor iterations that produced them.
     * @param timesSeconds which will be destructively sorted in place to extract percentiles.
     * @return the extracted travel times, in minutes. This is a hack to enable scoring paths in the caller.
     */
    public int[] recordTravelTimesForTarget (int target, int[] timesSeconds) {
        int[] percentileTravelTimes =  new int[nPercentiles];

        if (timesSeconds.length == 1) { // Handle results with no variation, e.g. from walking, biking, or driving.
            Arrays.fill(percentileTravelTimes, timesSeconds[0]);
        } else { // Handle results with variation
            percentileTravelTimes = getPercentileValues(percentileTravelTimes, timesSeconds);
        }

        // convert the selected percentile values from seconds to minutes
        for (int p = 0; p < nPercentiles; p ++){
            if (percentileTravelTimes[p] == FastRaptorWorker.UNREACHED) {
                // If a target is not reachable at percentile p, it won't be reachable at higher percentiles of
                // travel time.
                break;
            } else {
                // Int divide will floor; this is correct because value 0 has travel times of up to one minute, etc.
                // This means that anything less than a cutoff of (say) 60 minutes (in seconds) will have value 59,
                // which is what we want. But maybe converting to minutes before we actually export a binary format is tying
                // the backend and frontend (which makes use of UInt8 typed arrays) too closely.
                percentileTravelTimes[p] = percentileTravelTimes[p] / 60;
            }
        }

        if (retainTravelTimes) {
            timeGrid.setTarget(target, percentileTravelTimes);
        }
        if (calculateAccessibility) {
            // This x/y addressing can only work with one grid at a time,
            // needs to be made absolute to handle multiple different extents.
            for (int g = 0; g < targetValues.length; g++) {
                double amount = targetValues[g][target];
                for (int p = 0; p < nPercentiles; p++) {
                    for (int c = 0; c < RegionalWorkResult.CUTOFFS.length; c++) {
                        if (percentileTravelTimes[p] < RegionalWorkResult.CUTOFFS[c]) { // TODO less than or equal?
                            accessibilityResult.incrementAccessibility(0, c, p, amount);
                        }
                    }
                }
            }
        }
        return percentileTravelTimes;
    }

    private int[] getPercentileValues (int[] selectedPercentiles, int[] timesSeconds){
        // Sort the times at each target and read off percentiles at the pre-calculated indexes.
        if (timesSeconds.length == timesPerDestination) {
            // Instead of general purpose sort this could be done by performing a counting sort on the times,
            // converting them to minutes in the process and reusing the small histogram array (120 elements) which
            // should remain largely in processor cache. That's a lot of division though. Would need to be profiled.
            Arrays.sort(timesSeconds);
            for (int p = 0; p < nPercentiles; p++) {
                selectedPercentiles[p] = timesSeconds[percentileIndexes[p]];
            }
        } else {
            throw new ParameterException("You must supply the expected number of travel time values (or only one value).");
        }

        return selectedPercentiles;

    }

    /**
     * If no travel times to destinations have been streamed in by calling recordTravelTimesForTarget, the
     * TimeGrid will have a buffer full of UNREACHED. This allows shortcutting around
     * routing and propagation when the origin point is not connected to the street network.
     */
    public OneOriginResult finish () {
        return new OneOriginResult(timeGrid, accessibilityResult);
    }

}
