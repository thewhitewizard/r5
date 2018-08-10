package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.GridCache;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.transit.TransportNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a task to be performed as part of a regional analysis.
 */
public class RegionalTask extends AnalysisTask implements Cloneable {

    /**
     * Coordinates of origin cell in grid defined in AnalysisTask.
     *
     * Note that these do not override fromLat and fromLon; those must still be set separately. This is for future use
     * to allow use of arbitrary origin points.
     */
    public int x = -1, y = -1;

    /**
     * The grid key on S3 to compute access to. If this is not blank, the default TravelTimeSurfaceTask will be
     * overridden; returnInVehicleTimes, returnWaitTimes, and returnPaths will be set to false; and the returned results
     * will be an accessibility value per origin, rather than a grid of travel times from that origin.
     */
    public String grid;

    /**
     * An array of grid keys on S3 to compute access to. If this is not blank, the default TravelTimeSurfaceTask will be
     * overridden; returnInVehicleTimes, returnWaitTimes, and returnPaths will be set to false; and the returned results
     * will be an accessibility value per origin for each destination grid, rather than a grid of travel times from
     * that origin.
     * NOT YET IMPLEMENTED AND TESTED
     */
    public List <String> destinationKeys;

    // keyed on destinationKey, target index;
    public double [][] targetValues;


    @Override
    public Type getType() {
        return Type.REGIONAL_ANALYSIS;
    }

    @Override
    public boolean isHighPriority() {
        return false; // regional analysis tasks are not high priority
    }

    /**
     * Regional analyses use the extents of the destination opportunity grids as their destination extents.
     * We don't want to enqueue duplicate tasks with the same destination pointset extents, because it is more efficient
     * to compute travel time for a given destination only once, then accumulate multiple accessibility values
     * for multiple opportunities at that destination.
     */
    @Override
    public List<PointSet> getDestinations(TransportNetwork network, GridCache gridCache) {
        List<PointSet> pointSets = new ArrayList<>();

        if (makeStaticSite) {
            // In the special case where we're making a static site, a regional task is producing travel time grids.
            // This is unlike the usual case where regional tasks produce accessibility indicator values.
            // Because the time grids are not intended for one particular set of destinations,
            // they should cover the whole analysis region. This RegionalTask has its own bounds, which are the bounds
            // of the origin grid.
            // FIXME the following limits the destination grid bounds to be exactly those of the origin grid.
            // This could easily be done with pointSets.add(network.gridPointSet);
            // However we might not always want to search out to such a huge destination grid.
            pointSets.add(gridPointSetCache.get(this.zoom, this.west, this.north, this.width, this.height, network.gridPointSet));
            return pointSets;
        }

        if (destinationKeys == null){
            // A single grid is specified.  Get it and flatten it
            Grid grid = gridCache.get(this.grid);
            targetValues[0] = grid.getValuesInRowMajorOrder();
            pointSets.add(gridPointSetCache.get(grid, network.gridPointSet));
        } else {
            // Multiple grids specified. Add only the first one to pointSets.
            // TODO This block is unused while destinationKeys is not set.
            // FIXME we really shouldn't have two different implementations present, one for a list and one for a single grid.

            Grid grid = gridCache.get(destinationKeys.get(0));
            targetValues[0] = grid.getValuesInRowMajorOrder();
            pointSets.add(gridPointSetCache.get(grid, network.gridPointSet));

            for (int i = 1; i < destinationKeys.size(); i++) { // the first grid is already in the list
                if (grid.hasEqualExtents(gridCache.get(destinationKeys.get(i)))) { // if the next has same extents
                    grid = gridCache.get(destinationKeys.get(i));
                    // add values to targetValues
                    targetValues[i] = grid.getValuesInRowMajorOrder();
                } else {
                    // TODO handle case of different extents
                    throw new UnsupportedOperationException("All destination opportunity datasets in a given regional" +
                            " analysis must have the same extent!");
                }
            }
        }
        // Use the network point set as the base point set, so that the cached linkages are used
        return pointSets;
    }

    public RegionalTask clone () {
        return (RegionalTask) super.clone();
    }

    @Override
    public String toString() {
        // Having job ID and allows us to follow regional analysis progress in log messages.
        return "RegionalTask{" +
                "jobId=" + jobId +
                ", task=" + taskId +
                ", x=" + x +
                ", y=" + y +
                '}';
    }

}
