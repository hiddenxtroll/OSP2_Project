//Name: Daniel Khuu
//ID: 109372156

//I pledge my honor that all parts of this project were done by me individually
//and without collaboration with anybody else.

package osp.Threads;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.Vector;
import java.util.Enumeration;
import osp.Utilities.*;
import osp.IFLModules.*;
import osp.Tasks.*;
import osp.EventEngine.*;
import osp.Hardware.*;
import osp.Devices.*;
import osp.Memory.*;
import osp.Resources.*;

//MMU.getPTBR() gets you the page table of the current task
//getTask() gets the task from the page table
//getCurrentThread() gets the current thread from the task

//Context Switch:
// Pre-Empty
// 1. Change state of thread to ThreadWaiting/ThreadReady.
// 2. Get current thread using method above
// 3. Set PTBR to null.
// 4. Change the current league of the previous task to null.

/**
   This class is responsible for actions related to threads, including
   creating, killing, dispatching, resuming, and suspending threads.

   @OSPProject Threads
*/
public class ThreadCB extends IflThreadCB
{

    private static PriorityQueue<ThreadCB> thread_queue;
    private long total_time;
    private long startTime;
    private long endTime;

    /**
       The thread constructor. Must call 

           super();

       as its first statement.

       @OSPProject Threads
    */
    public ThreadCB()
    {
        super();
        //each thread will have a total_time counter, which will store how much time each thread was running for
        this.total_time = 0;
        this.startTime = 0;
        this.endTime = 0;
    }

    /**
       This method will be called once at the beginning of the
       simulation. The student can set up static variables here.
       
       @OSPProject Threads
    */
    public static void init()
    {
        thread_queue = new PriorityQueue<ThreadCB>(new Comparator<ThreadCB>(){
            @Override
            public int compare(ThreadCB a, ThreadCB b){
                if(a.getTime() > b.getTime()) //the lower CPU time gets the higher priority
                    return 1;
                else if(a.getTime() < b.getTime())
                    return -1;
                else
                    return 0;
        }
        });

    }

    /** 
        Sets up a new thread and adds it to the given task. 
        The method must set the ready status 
        and attempt to add thread to task. If the latter fails 
        because there are already too many threads in this task, 
        so does this method, otherwise, the thread is appended 
        to the ready queue and dispatch() is called.

    The priority of the thread can be set using the getPriority/setPriority
    methods. However, OSP itself doesn't care what the actual value of
    the priority is. These methods are just provided in case priority
    scheduling is required.

    @return thread or null

        @OSPProject Threads
    */
    static public ThreadCB do_create(TaskCB task)
    {

        if(task.getThreadCount() >= MaxThreadsPerTask || task == null){
            dispatch(); //we do this, so the next call will work!
            return null;
        }
        
        ThreadCB newThread = new ThreadCB();
        newThread.setPriority(task.getPriority());
        newThread.setTask(task);
        newThread.setStatus(ThreadReady);

        if(task.addThread(newThread) == FAILURE){
            dispatch();
            return null;
        }

        thread_queue.add(newThread);
        dispatch();
        return newThread;

    }

    /** 
    Kills the specified thread. 

    The status must be set to ThreadKill, the thread must be
    removed from the task's list of threads and its pending IORBs
    must be purged from all device queues.
        
    If some thread was on the ready queue, it must removed, if the 
    thread was running, the processor becomes idle, and dispatch() 
    must be called to resume a waiting thread.
    
    @OSPProject Threads
    */
    public void do_kill()
    {
        
        if(this.getStatus() == ThreadReady){
            thread_queue.remove(this);
        }
        else if(this.getStatus() == ThreadRunning){
            ThreadCB removeThread = null;
            try{
                removeThread = MMU.getPTBR().getTask().getCurrentThread();
                if(this == removeThread){
                    MMU.getPTBR().getTask().setCurrentThread(null);
                    MMU.setPTBR(null);
                }
            }catch(Exception e){
                //EXCEPTION.
            }
        }
        //nothing special to do for ThreadWaiting

        //cancelling the IO
        for(int i = 0; i < Device.getTableSize(); i++){
            Device.get(i).cancelPendingIO(this);
        }

        //System.out.println("KILLED " + this.getTime()); 
        this.setStatus(ThreadKill); //killing for ALL ThreadReady, ThreadWaiting, and ThreadRunning --> ThreadKill
        getTask().removeThread(this);

        ResourceCB.giveupResources(this);

        dispatch(); //dispatch a new thread

        if(getTask().getThreadCount() == 0){
            getTask().kill();
        }

    }

    /** Suspends the thread that is currenly on the processor on the 
        specified event. 

        Note that the thread being suspended doesn't need to be
        running. It can also be waiting for completion of a pagefault
        and be suspended on the IORB that is bringing the page in.
    
    Thread's status must be changed to ThreadWaiting or higher,
        the processor set to idle, the thread must be in the right
        waiting queue, and dispatch() must be called to give CPU
        control to some other thread.

    @param event - event on which to suspend this thread.

        @OSPProject Threads
    */
    public void do_suspend(Event event)
    {
        if(this.getStatus() == ThreadRunning){
            this.setStatus(ThreadWaiting);
            //set the current thread to null
            this.getTask().setCurrentThread(null);

            //Calculating how long the thread has been running for and storing it
            long stoppingTime = HClock.get();
            this.setEndTime(stoppingTime);

            long start = this.getStartTime();
            long previousTime = this.getTime();

            this.setTime(previousTime + (stoppingTime - start));
        }
        else if(this.getStatus() >= ThreadWaiting){
            this.setStatus(this.getStatus() + 1); //check this math
        }

        event.addThread(this);
        thread_queue.remove(this); //will be added back in the resume method
        dispatch();

    }

    /** Resumes the thread.
        
    Only a thread with the status ThreadWaiting or higher
    can be resumed.  The status must be set to ThreadReady or
    decremented, respectively.
    A ready thread should be placed on the ready queue.
    
    @OSPProject Threads
    */
    public void do_resume()
    {
        if(this.getStatus() == ThreadWaiting){
            this.setStatus(ThreadReady);
            thread_queue.add(this);
        }
        else{
            this.setStatus(this.getStatus() - 1);
        }
        dispatch();

    }

    /** 
        Selects a thread from the run queue and dispatches it. 

        If there is just one theread ready to run, reschedule the thread 
        currently on the processor.

        In addition to setting the correct thread status it must
        update the PTBR.
    
    @return SUCCESS or FAILURE

        @OSPProject Threads
    */
    public static int do_dispatch()
    {
        //THE THREAD IS SCHEDULED USING CPU TIME PRIORITY

        ThreadCB preempty_thread = null;
        ThreadCB timing_thread = null;

        try{
            //getting the current thread so that we can pre-empty it
            preempty_thread = MMU.getPTBR().getTask().getCurrentThread();
            
        }catch(Exception e){
            //EXCEPTION
        }

        //CONTEXT SWITCH

        //Pre-Emptying a Thread (ThreadRunning -> ThreadReady)
        if(preempty_thread != null){
            //THE CURRENT THREAD IS EITHER FINISHED BY 100 OR AUTOMATICALLY
  
            preempty_thread.setStatus(ThreadReady);
            MMU.getPTBR().getTask().setCurrentThread(null);
            MMU.setPTBR(null);

            long stoppingTime = HClock.get();
            preempty_thread.setEndTime(stoppingTime);

            long start = preempty_thread.getStartTime();
            long previousTime = preempty_thread.getTime();

            preempty_thread.setTime(previousTime + (stoppingTime - start));
            thread_queue.add(preempty_thread);
        }

        if(!thread_queue.isEmpty()){
            //Dispatching a Thread (ThreadReady -> ThreadRunning)
            
            //PICKING THE THREAD WITH THE LEAST CPU TIME TO RUN FROM THE PRIORITYQUEUE
            timing_thread = thread_queue.poll();

            MMU.setPTBR(timing_thread.getTask().getPageTable());
            timing_thread.getTask().setCurrentThread(timing_thread);

            timing_thread.setStatus(ThreadRunning);
            timing_thread.setStartTime(HClock.get());
            HTimer.set(100);

            return SUCCESS;
        }

        MMU.setPTBR(null);
        return FAILURE;

    }

    /**
       Called by OSP after printing an error message. The student can
       insert code here to print various tables and data structures in
       their state just after the error happened.  The body can be
       left empty, if this feature is not used.

       @OSPProject Threads
    */
    public static void atError()
    {
        // your code goes here

    }

    /** Called by OSP after printing a warning message. The student
        can insert code here to print various tables and data
        structures in their state just after the warning happened.
        The body can be left empty, if this feature is not used.
       
        @OSPProject Threads
     */
    public static void atWarning()
    {
        // your code goes here

    }

    public long getTime(){
        return total_time;
    }

    public void setTime(long total_time){
        this.total_time = total_time;
    }

    public long getStartTime(){
        return startTime;
    }

    public void setStartTime(long startTime){
        this.startTime = startTime;
    }

    public long getEndTime(){
        return endTime;
    }

    public void setEndTime(long endTime){
        this.endTime = endTime;
    }

}

/*
      Feel free to add local classes to improve the readability of your code
*/