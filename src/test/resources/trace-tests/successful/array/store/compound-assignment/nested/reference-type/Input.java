class Input {

    public Integer[] getArray() {
        return new Integer[] {1};
    }

    public int getIndex() {
        return 0;
    }

    public Integer getValue(int i) {
        if (i == 0) {
            return 1;
        }
        return 2;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.getArray()[obj.getIndex()] += obj.getValue(0);
    }
}
