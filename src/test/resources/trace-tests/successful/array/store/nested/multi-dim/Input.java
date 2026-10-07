class Input {

    public int[][] getArray() {
        return new int[5][5];
    }

    public int getIndex(int i) {
        if (i == 1) {
            return 1;
        }
        return 0;
    }

    public int getValue() {
        return 2;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.getArray()[obj.getIndex(1)][obj.getIndex(2)] = obj.getValue();
    }
}
