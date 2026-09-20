class Quicksort {

    /*@ public normal_behaviour
      @
      @  //-- the following ensures with seqPerm cannot be shown in auto mode with default options:
      @  //ensures \dl_seqPerm(\dl_array2seq(array), \old(\dl_array2seq(array)));
      @  //-- equivalent ensures with \num_of:
      @  ensures (\forall int j; 0<=j && j < array.length;
      @               (\num_of int i; 0<=i && i < array.length; \old(array[i]) == array[j])
      @            == (\num_of int i; 0<=i && i < array.length;      array[i]  == array[j])
      @          );
      @
      @  ensures (\forall int i; 0<=i && i<array.length-1; array[i] <= array[i+1]);
      @
      @
      @  assignable array[*], tracer.Trace.index;
      @*/
    public static void sort(int[] array) {
        if(array.length > 0) { // 0
            sort(array, 0, array.length-1);
        }
    }

    private static void sort(int[] array, int from, int to) {
        if(from < to) { // 1, 12, 21
            int splitPoint = split(array, from, to);
            sort(array, from, splitPoint-1);
            sort(array, splitPoint+1, to);
        } // 11, 20, 25, 26
    }

    private static int split(int[] array, int from, int to) {

        int i = from;
        int pivot = array[to];

        for(int j = from; j < to; j++) { // 2, 4, 6, 8, 13, 15, 17, 22
            if(array[j] <= pivot) { // 16
                int t = array[i];
                array[i] = array[j];
                array[j] = t;
                i++;
            } // 3, 5, 7, 9, 14, 18, 23
        } // 10, 19, 24

        array[to] = array[i];
        array[i] = pivot;

        return i;

    }
}