class Input {
    Input next;

    Input(Input n) {
        this.next = n;
    }

    Input getNext() {
        return this.next;
    }

    public static void main(String[] args) {
        Input inner = new Input(null);
        Input outer = new Input(inner);
        Input result = outer.getNext().getNext();
    }
}
