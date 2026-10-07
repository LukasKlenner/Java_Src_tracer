class Input {

    public int[] getArray() {
        return new int[5];
    }

    public int getIndex() {
        return 0;
    }

    public int getValue(int i) {
        if (i == 0) {
            return 1;
        }
        return 2;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.getArray()[obj.getIndex()] = obj.getValue(0);
    }
}
