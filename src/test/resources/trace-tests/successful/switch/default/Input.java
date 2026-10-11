class Input {

    void m() {

    }

    public static void main(String[] args) {
        Input obj = new Input();
        int a = 100;

        switch (a) {
            case 1: {
                int x = 1;
                break;
            }
            case 2: {
                int y = 2;
                break;
            }
            default: {
                obj.m();
                break;
            }
        }
    }
}
