class Input {

    public Integer[] getArray() {
        return new Integer[] {1};
    }

    public int getIndex() {
        return 0;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.getArray()[obj.getIndex()]++;
    }
}
