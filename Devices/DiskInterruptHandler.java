/*Name: Daniel Khuu
ID: 109372156

I pledge my honor that all parts of this project were done by me individually
and without collaboration with anybody else.*/

package osp.Devices;
import java.util.*;
import osp.IFLModules.*;
import osp.Hardware.*;
import osp.Interrupts.*;
import osp.Threads.*;
import osp.Utilities.*;
import osp.Tasks.*;
import osp.Memory.*;
import osp.FileSys.*;

/**
    The disk interrupt handler.  When a disk I/O interrupt occurs,
    this class is called upon the handle the interrupt.

    @OSPProject Devices
*/
public class DiskInterruptHandler extends IflDiskInterruptHandler
{
    /** 
        Handles disk interrupts. 
        
        This method obtains the interrupt parameters from the 
        interrupt vector. The parameters are IORB that caused the 
        interrupt: (IORB)InterruptVector.getEvent(), 
        and thread that initiated the I/O operation: 
        InterruptVector.getThread().
        The IORB object contains references to the memory page 
        and open file object that participated in the I/O.
        
        The method must unlock the page, set its IORB field to null,
        and decrement the file's IORB count.
        
        The method must set the frame as dirty if it was memory write 
        (but not, if it was a swap-in, check whether the device was 
        SwapDevice)

        As the last thing, all threads that were waiting for this 
        event to finish, must be resumed.

        @OSPProject Devices 
    */
    public void do_handleInterrupt()
    {
        IORB current = (IORB) InterruptVector.getEvent(); //this event has the IORB that caused the interrupt
        ThreadCB thread = InterruptVector.getThread();
        int current_to_idle = current.getDeviceID(); //there is one device for multiple IORBS and threads
        Device current_device = Device.get(current_to_idle);

        OpenFile current_open = current.getOpenFile();
        current_open.decrementIORBCount();

        //close the file
        if(current_open.getIORBCount() == 0){
            if(current_open.closePending == true){
        	    current_open.close();
                current_device.do_cancelPendingIO(thread);
            }
        }

        current.getPage().unlock();

        if(current.getDeviceID() != SwapDeviceID){ //it's not swapped
            if(thread.getTask().getStatus() != TaskTerm)
            	current.getPage().getFrame().setReferenced(true);

            if(current.getIOType() == FileRead){
                if(thread.getTask().getStatus() == TaskLive) //to see if the task associated with thread is still alive
                    current.getPage().getFrame().setDirty(true);
            }
        }else{ //it is swapped
            if(thread.getTask().getStatus() == TaskLive)
                current.getPage().getFrame().setDirty(false);
        }

        if(thread.getTask().getStatus() == TaskTerm && current.getPage().getFrame().getReserved() == thread.getTask()){
            current.getPage().getFrame().setUnreserved(thread.getTask());
        }

        current.notifyThreads();

        current_device.setBusy(false);

        IORB newReq = null;
        if((newReq = current_device.dequeueIORB()) != null)
            current_device.startIO(newReq);

        ThreadCB.dispatch();

    }


    /*
       Feel free to add methods/fields to improve the readability of your code
    */

}

/*
      Feel free to add local classes to improve the readability of your code
*/
