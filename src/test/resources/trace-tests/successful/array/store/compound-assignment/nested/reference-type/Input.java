class Input {

    public Integer[] getArray() {
        return new Integer[] {1};
    }


    public Integer getValue(int i) {
        if (i == 0) {
            return 0;
        }
        return 1;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.getArray()[obj.getValue(0)] += obj.getValue(1);
    }
}
