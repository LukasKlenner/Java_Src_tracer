class Input {

    public int[] getArray() {
        return new int[3];
    }

    public int getIndex() {
        return 0;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int x = obj.getArray()[obj.getIndex()];
    }
}
