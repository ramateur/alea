/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package xklusac.algorithms.queue_based.multi_queue;

import gridsim.GridSim;
import gridsim.GridSimTags;
import gridsim.Gridlet;
import gridsim.MachineList;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import xklusac.algorithms.SchedulingPolicy;
import xklusac.environment.ExperimentSetup;
import xklusac.environment.GridletInfo;
import xklusac.environment.MachineWithRAM;
import xklusac.environment.ResourceInfo;
import xklusac.environment.Scheduler;

/**
 * Class EASY_Backfilling<p>
 * Implements EASY BAckfilling.
 *
 * @author Dalibor Klusacek
 */
public class EASY_Backfilling implements SchedulingPolicy {

    private Scheduler scheduler;
    private ResourceInfo reservedResource;

    public EASY_Backfilling(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void addNewJob(GridletInfo gi) {
        double runtime1 = new Date().getTime();
        int index = Scheduler.all_queues_names.indexOf(gi.getQueue());
        if (index == -1 || ExperimentSetup.by_queue == false) {
            index = 0;
        }
        LinkedList queue = Scheduler.all_queues.get(index);
        queue.addLast(gi);
        Scheduler.runtime += (new Date().getTime() - runtime1);
        
    }

    @Override
    public int selectJob() {
        //System.out.println("Selecting job by EASY Backfilling...");
        int scheduled = 0;
        boolean succ = false;
        double est = 0.0;
        ResourceInfo r_cand = null;
        int r_cand_speed = 0;

        for (int q = 0; q < Scheduler.all_queues.size(); q++) {
            Scheduler.queue = Scheduler.all_queues.get(q);            
            if (Scheduler.queue.size() > 0) {
                GridletInfo gi = (GridletInfo) Scheduler.queue.getFirst();
                
                for (int j = 0; j < Scheduler.resourceInfoList.size(); j++) {
                    ResourceInfo ri = (ResourceInfo) Scheduler.resourceInfoList.get(j);
                    if (Scheduler.isSuitable(ri, gi) && ri.canExecuteNow(gi)) {
                        int speed = ri.peRating;
                        if (speed > r_cand_speed) {
                            r_cand = ri;
                            r_cand_speed = speed;
                        }
                    }
                }

                if (r_cand != null) {
                    gi = (GridletInfo) Scheduler.queue.removeFirst();
                    r_cand.addGInfoInExec(gi);
                    // set the resource ID for this gridletInfo (this is the final scheduling decision)
                    gi.setResourceID(r_cand.resource.getResourceID());
                    // tell the JSS where to send which gridlet
                    scheduler.submitJob(gi.getGridlet(), r_cand.resource.getResourceID());
                    succ = true;
                    r_cand.is_ready = true;
                    //scheduler.sim_schedule(GridSim.getEntityId("Alea_3.0_scheduler"),  0.0, AleaSimTags.GRIDLET_SENT, gi);
                    return 1;
                }
            } 
            // try backfilling procedure
            if (!succ && Scheduler.queue.size() > 1) {
                boolean removed = false;
                // do not create reservation for job that cannot be executed
                for (int j = 0; j < Scheduler.queue.size(); j++) {

                    GridletInfo gi = (GridletInfo) Scheduler.queue.get(j);
                    if (Scheduler.isExecutable(gi)) {
                        break;
                    } else {
                        // kill such job
                        System.out.println(Math.round(GridSim.clock()) + " gi:" + gi.getID() + ": KILLED BY EASY-BACKFILLING: [" + gi.getProperties() + "] CPUs=" + gi.getNumPE());
                        try {
                            gi.getGridlet().setGridletStatus(Gridlet.CANCELED);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                        removed = true;
                        scheduler.sim_schedule(GridSim.getEntityId("Alea_3.0_scheduler"), 0.0, GridSimTags.GRIDLET_RETURN, gi.getGridlet());
                        Scheduler.queue.remove(j);
                        j--;
                    }
                }
                // EASY will be called again when killed jobs return o Scheduler - no waiting will happen.
                if (removed) {
                    return 0;
                    // head of queue - gridlet with reservation
                }
                GridletInfo grsv = (GridletInfo) Scheduler.queue.get(0);
                // reserved machine (i.e. Earliest Available)
                ResourceInfo rsv_res = findReservedResource(grsv);
                reservedResource = rsv_res;

                // try backfilling on all gridlets in queue except for head (grsv)
                for (int j = 1; j < Scheduler.queue.size(); j++) {
                    GridletInfo gi = (GridletInfo) Scheduler.queue.get(j);
                    /*if (gi.getNumPE() >= grsv.getNumPE()) {
                     continue; // such gridlet will never succeed (not true if requirements used)
                     TODO
                     }*/
                    ResourceInfo ri = findResourceBF(gi);
                    if (ri != null) {
                        Scheduler.queue.remove(j);
                        ri.addGInfoInExec(gi);
                        // set the resource ID for this gridletInfo (this is the final scheduling decision)
                        gi.setResourceID(ri.resource.getResourceID());
                        // submit job
                        scheduler.submitJob(gi.getGridlet(), ri.resource.getResourceID());
                        ri.is_ready = true;
                        succ = true;
                        //scheduler.sim_schedule(GridSim.getEntityId("Alea_3.0_scheduler"), 0.0, AleaSimTags.GRIDLET_SENT, gi);

                        scheduled++;
                        j--; //to get correct gridlet from queue in next round. The queue was shortened...
                        return 1;

                    }
                }
            }
            //if(scheduled>0)System.out.println(queue.size()+" remain, backfilled = "+scheduled);
        }//next queue
        return scheduled;
    }

    /**
     * auxiliary method needed for easy/edf backfilling
     */
    private ResourceInfo findResourceBF(GridletInfo gi) {
        ResourceInfo r_cand = null;
        int r_cand_speed = 0;
        for (int j = 0; j < Scheduler.resourceInfoList.size(); j++) {
            ResourceInfo ri = (ResourceInfo) Scheduler.resourceInfoList.get(j);
            if (Scheduler.isSuitable(ri, gi) && ri.canExecuteNow(gi) && ri.resource.getResourceID() != reservedResource.resource.getResourceID()) {
                int speed = ri.peRating;
                if (speed >= r_cand_speed) {
                    r_cand = ri;
                    r_cand_speed = speed;
                }

            } else if (Scheduler.isSuitable(ri, gi) && ri.canExecuteNow(gi) && ri.resource.getResourceID() == reservedResource.resource.getResourceID()) {
                boolean collidate = collidateWithReservation(gi, ri);
                if (collidate == false) {
                    int speed = ri.peRating;
                    if (speed > r_cand_speed) {
                        r_cand = ri;
                        r_cand_speed = speed;
                    }
                }
            }
        }
        return r_cand;
    }

    /**
     * Auxiliary method for easy/edf backfilling
     */
    private ResourceInfo findReservedResource(GridletInfo grsv) {
        double est = Double.MAX_VALUE;
        ResourceInfo found = null;
        for (int j = 0; j < Scheduler.resourceInfoList.size(); j++) {
            ResourceInfo ri = (ResourceInfo) Scheduler.resourceInfoList.get(j);
            if (Scheduler.isSuitable(ri, grsv)) {
                double ri_est = ri.getEarliestStartTime(grsv, GridSim.clock());
                // select minimal EST
                if (ri_est <= est) {
                    est = ri_est;
                    found = ri;
                }
            } else {
                continue; // this is not suitable cluster
            }
        }
        return found;
    }  
    
    /**
     * Auxiliary method for easy backfilling.
     * Check if given gridlet can be started without colliding reserved gridlet
     */
    private boolean collidateWithReservation(GridletInfo gi, ResourceInfo ri) {
        double eft = GridSim.clock() + gi.getJobRuntime(reservedResource.peRating);
        if (ExperimentSetup.use_RAM == false) {
            if ((eft < reservedResource.est) || reservedResource.usablePEs >= gi.getNumPE()) {
                return false;
            }

        } else {     
            int allocateNodes = gi.getNumNodes();
            //test if gridlet can be finished earlier than start of reservation
            if (eft < reservedResource.est) {
                return false;
            }
            
            //else test if gridlet can be started without postponing reservation
            MachineList machines = ri.resource.getMachineList();
            MachineList estimatedMachines = ri.getEstMachines();
            for (int i = 0; i < machines.size(); i++) {
                MachineWithRAM machine = (MachineWithRAM) machines.get(i);
                // cannot use such machine
                if (machine.getFailed()) {
                    continue;
                }
                if (machine.getNumFreePE() >= gi.getPpn() && machine.getFreeRam() >= gi.getRam()) {
                    //if this machine is used by reservation, test if there is space for backfilled gridlet
                    if(ri.getReservedMachineIDs().contains(machine.getMachineID())){
                        MachineWithRAM resMachine = (MachineWithRAM) estimatedMachines.getMachine(machine.getMachineID());
                        if (resMachine.getNumFreePE() < gi.getPpn() || resMachine.getFreeRam() < gi.getRam()){
                            continue;
                        }
                    }
                    allocateNodes--;
                }
                if (allocateNodes <= 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
