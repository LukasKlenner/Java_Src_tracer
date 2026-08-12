package srctracer.instrumenter.visitors;

public class InstrumenterStats {

    private int methods = 0;
    private int ifs = 0;
    private int returns = 0;
    private int loops = 0;
    private int switches = 0;
    private int tries = 0;
    private int mains = 0;
    private int initializers = 0;

    public void incrementMethodCount() {
        methods++;
    }

    public void incrementIfCount() {
        ifs++;
    }

    public void incrementReturnCount() {
        returns++;
    }

    public void incrementLoopCount() {
        loops++;
    }

    public void incrementSwitchCount() {
        switches++;
    }

    public void incrementTryCount() {
        tries++;
    }

    public void incrementMainCount() {
        mains++;
    }

    public void incrementInitializerCount() {
        initializers++;
    }

    public int getMethodCount() {
        return methods;
    }

    public int getIfCount() {
        return ifs;
    }

    public int getReturnCount() {
        return returns;
    }

    public int getLoopCount() {
        return loops;
    }

    public int getSwitchCount() {
        return switches;
    }

    public int getTryCount() {
        return tries;
    }

    public int getMainCount() {
        return mains;
    }

    public int getInitializerCount() {
        return initializers;
    }

    public String getStatsSummary() {
        return "Stats: " +
                methods + " methods, " +
                initializers + " initializers, " +
                ifs + " if, " +
                returns + " return, " +
                mains + " main wrapped, " +
                loops + " loop, " +
                switches + " switch, " +
                tries + " try";
    }

}
