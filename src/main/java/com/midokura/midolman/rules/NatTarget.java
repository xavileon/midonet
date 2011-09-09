package com.midokura.midolman.rules;

public class NatTarget {

    public int nwStart;
    public int nwEnd;
    public short tpStart;
    public short tpEnd;

    public NatTarget(int nwStart, int nwEnd, short tpStart, short tpEnd) {
        this.nwStart = nwStart;
        this.nwEnd = nwEnd;
        this.tpStart = tpStart;
        this.tpEnd = tpEnd;
    }
	
	// Default constructor for the Jackson deserialization.
	public NatTarget() { super(); }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NatTarget))
            return false;
        NatTarget nt = (NatTarget) other;
        return nwStart == nt.nwStart && nwEnd == nt.nwEnd
                && tpStart == nt.tpStart && tpEnd == nt.tpEnd;
    }

    @Override
    public int hashCode() {
        int hash = nwStart;
        hash = 13 * hash + nwEnd;
        hash = 17 * hash + tpStart;
        return 23 * hash + tpEnd;
    }
}
