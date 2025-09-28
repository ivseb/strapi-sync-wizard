/**
 * Raggruppa un array di oggetti per una chiave specifica con opzioni di sorting avanzate
 * @param array - Array di oggetti da raggruppare
 * @param keyFn - Funzione che estrae la chiave di raggruppamento da ogni oggetto
 * @param options - Opzioni per il sorting
 * @returns Oggetto con le chiavi di raggruppamento e i relativi array di oggetti
 */
interface GroupByOptions<T, K extends string | number> {
  /** Se ordinare le chiavi del risultato */
  sortKeys?: boolean;
  /** Funzione personalizzata per ordinare le chiavi */
  keySortFn?: (a: K, b: K) => number;
  /** Funzione personalizzata per ordinare gli elementi all'interno di ogni gruppo */
  itemSortFn?: (a: T, b: T) => number;
}

function groupBy<T, K extends string | number>(
    array: T[],
    keyFn: (item: T) => K,
    options: GroupByOptions<T, K> = {}
): Record<K, T[]> {
  const { sortKeys = false, keySortFn, itemSortFn } = options;

  const grouped = array.reduce((acc, item) => {
    const key = keyFn(item);
    if (!acc[key]) {
      acc[key] = [];
    }
    acc[key].push(item);
    return acc;
  }, {} as Record<K, T[]>);

  // Ordina gli elementi all'interno di ogni gruppo se specificato
  if (itemSortFn) {
    Object.keys(grouped).forEach(key => {
      grouped[key as K].sort(itemSortFn);
    });
  }

  // Ordina le chiavi se richiesto
  if (sortKeys || keySortFn) {
    const keys = Object.keys(grouped) as K[];
    const sortedKeys = keySortFn ? keys.sort(keySortFn) : keys.sort();

    const sortedGrouped = {} as Record<K, T[]>;
    sortedKeys.forEach(key => {
      sortedGrouped[key] = grouped[key];
    });
    return sortedGrouped;
  }

  return grouped;
}

/**
 * Versione alternativa che restituisce un array di gruppi con chiave e valori
 */
interface GroupEntry<K, T> {
  key: K;
  items: T[];
}

function groupByToArray<T, K extends string | number>(
    array: T[],
    keyFn: (item: T) => K,
    options: GroupByOptions<T, K> = {}
): GroupEntry<K, T>[] {
  const grouped = groupBy(array, keyFn, options);

  return (Object.keys(grouped) as K[]).map(key => ({
    key,
    items: grouped[key]
  }));
}

/**
 * Funzioni helper per iterazione type-safe
 */
const groupByHelpers = {
  /**
   * Itera sui gruppi con tipizzazione corretta
   */
  forEach: <T, K extends string | number>(
      grouped: Record<K, T[]>,
      callback: (key: K, items: T[], index: number) => void
  ): void => {
    const keys = Object.keys(grouped) as K[];
    keys.forEach((key, index) => {
      callback(key, grouped[key], index);
    });
  },

  /**
   * Mappa sui gruppi con tipizzazione corretta
   */
  map: <T, K extends string | number, R>(
      grouped: Record<K, T[]>,
      callback: (key: K, items: T[], index: number) => R
  ): R[] => {
    const keys = Object.keys(grouped) as K[];
    return keys.map((key, index) => {
      return callback(key, grouped[key], index);
    });
  },

  /**
   * Filtra i gruppi con tipizzazione corretta
   */
  filter: <T, K extends string | number>(
      grouped: Record<K, T[]>,
      predicate: (key: K, items: T[], index: number) => boolean
  ): Record<K, T[]> => {
    const result = {} as Record<K, T[]>;
    const keys = Object.keys(grouped) as K[];

    keys.forEach((key, index) => {
      if (predicate(key, grouped[key], index)) {
        result[key] = grouped[key];
      }
    });

    return result;
  },

  /**
   * Restituisce le chiavi tipizzate
   */
  keys: <T, K extends string | number>(grouped: Record<K, T[]>): K[] => {
    return Object.keys(grouped) as K[];
  },

  /**
   * Restituisce i valori tipizzati
   */
  values: <T, K extends string | number>(grouped: Record<K, T[]>): T[][] => {
    return Object.values(grouped);
  },

  /**
   * Restituisce entries tipizzate
   */
  entries: <T, K extends string | number>(grouped: Record<K, T[]>): [K, T[]][] => {
    const keys = Object.keys(grouped) as K[];
    return keys.map(key => [key, grouped[key]] as [K, T[]]);
  }
};

export { groupBy, groupByToArray, groupByHelpers, type GroupByOptions, type GroupEntry };