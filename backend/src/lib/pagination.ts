export function paginate(page = 1, limit = 20) {
  const take = Math.min(Math.max(limit, 1), 100);
  const skip = (Math.max(page, 1) - 1) * take;
  return { take, skip, page: Math.max(page, 1) };
}

export function paginatedResponse<T>(items: T[], total: number, page: number, limit: number) {
  return {
    items,
    total,
    page,
    totalPages: Math.ceil(total / limit),
    hasMore: page * limit < total,
  };
}
